/*
 * Copyright 2016 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.ServiceConfigUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that provides round-robin load balancing mechanism over the
 * addresses from the {@link NameResolver}.  The sub-lists received from the name resolver
 * are considered to be an {@link EquivalentAddressGroup} and each of these sub-lists is
 * what is then balanced across.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public final class RoundRobinLoadBalancerFactory extends LoadBalancer.Factory {

  private static final RoundRobinLoadBalancerFactory INSTANCE =
      new RoundRobinLoadBalancerFactory();

  private RoundRobinLoadBalancerFactory() {}

  /**
   * A lighter weight Reference than AtomicReference.
   */
  @VisibleForTesting
  static final class Ref<T> {
    T value;

    Ref(T value) {
      this.value = value;
    }
  }

  /**
   * Gets the singleton instance of this factory.
   */
  public static RoundRobinLoadBalancerFactory getInstance() {
    return INSTANCE;
  }

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new RoundRobinLoadBalancer(helper);
  }

  @VisibleForTesting
  static final class RoundRobinLoadBalancer extends LoadBalancer {
    @VisibleForTesting
    static final Attributes.Key<Ref<ConnectivityStateInfo>> STATE_INFO =
        Attributes.Key.create("state-info");
    // package-private to avoid synthetic access
    static final Attributes.Key<Ref<Subchannel>> STICKY_REF = Attributes.Key.create("sticky-ref");

    private static final Logger logger = Logger.getLogger(RoundRobinLoadBalancer.class.getName());

    private final Helper helper;
    private final Map<EquivalentAddressGroup, Subchannel> subchannels =
        new HashMap<EquivalentAddressGroup, Subchannel>();
    private final Random random;

    @Nullable
    private StickinessState stickinessState;

    RoundRobinLoadBalancer(Helper helper) {
      this.helper = checkNotNull(helper, "helper");
      this.random = new Random();
    }

    @Override
    public void handleResolvedAddressGroups(
        List<EquivalentAddressGroup> servers, Attributes attributes) {
      Set<EquivalentAddressGroup> currentAddrs = subchannels.keySet();
      Set<EquivalentAddressGroup> latestAddrs = stripAttrs(servers);
      Set<EquivalentAddressGroup> addedAddrs = setsDifference(latestAddrs, currentAddrs);
      Set<EquivalentAddressGroup> removedAddrs = setsDifference(currentAddrs, latestAddrs);

      Map<String, Object> serviceConfig =
          attributes.get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG);
      if (serviceConfig != null) {
        String stickinessMetadataKey =
            ServiceConfigUtil.getStickinessMetadataKeyFromServiceConfig(serviceConfig);
        if (stickinessMetadataKey != null) {
          if (stickinessMetadataKey.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
            logger.log(
                Level.FINE,
                "Binary stickiness header is not supported. The header '{0}' will be ignored",
                stickinessMetadataKey);
          } else if (stickinessState == null
              || !stickinessState.key.name().equals(stickinessMetadataKey)) {
            stickinessState = new StickinessState(stickinessMetadataKey);
          }
        }
      }

      // Create new subchannels for new addresses.
      for (EquivalentAddressGroup addressGroup : addedAddrs) {
        // NB(lukaszx0): we don't merge `attributes` with `subchannelAttr` because subchannel
        // doesn't need them. They're describing the resolved server list but we're not taking
        // any action based on this information.
        Attributes.Builder subchannelAttrs = Attributes.newBuilder()
            // NB(lukaszx0): because attributes are immutable we can't set new value for the key
            // after creation but since we can mutate the values we leverage that and set
            // AtomicReference which will allow mutating state info for given channel.
            .set(STATE_INFO,
                new Ref<ConnectivityStateInfo>(ConnectivityStateInfo.forNonError(IDLE)));

        Ref<Subchannel> stickyRef = null;
        if (stickinessState != null) {
          subchannelAttrs.set(STICKY_REF, stickyRef = new Ref<Subchannel>(null));
        }

        Subchannel subchannel = checkNotNull(
            helper.createSubchannel(addressGroup, subchannelAttrs.build()), "subchannel");
        if (stickyRef != null) {
          stickyRef.value = subchannel;
        }
        subchannels.put(addressGroup, subchannel);
        subchannel.requestConnection();
      }

      // Shutdown subchannels for removed addresses.
      for (EquivalentAddressGroup addressGroup : removedAddrs) {
        Subchannel subchannel = subchannels.remove(addressGroup);
        subchannel.shutdown();
      }

      updateBalancingState(getAggregatedState(), getAggregatedError());
    }

    @Override
    public void handleNameResolutionError(Status error) {
      updateBalancingState(TRANSIENT_FAILURE, error);
    }

    @Override
    public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      if (stateInfo.getState() == SHUTDOWN && stickinessState != null) {
        stickinessState.remove(subchannel);
      }
      if (subchannels.get(subchannel.getAddresses()) != subchannel) {
        return;
      }
      if (stateInfo.getState() == IDLE) {
        subchannel.requestConnection();
      }
      getSubchannelStateInfoRef(subchannel).value = stateInfo;
      updateBalancingState(getAggregatedState(), getAggregatedError());
    }

    @Override
    public void shutdown() {
      for (Subchannel subchannel : getSubchannels()) {
        subchannel.shutdown();
      }
    }

    /**
     * Updates picker with the list of active subchannels (state == READY).
     */
    private void updateBalancingState(ConnectivityState state, Status error) {
      List<Subchannel> activeList = filterNonFailingSubchannels(getSubchannels());
      // initialize the Picker to a random start index to ensure that a high frequency of Picker
      // churn does not skew subchannel selection.
      int startIndex = activeList.isEmpty() ? 0 : random.nextInt(activeList.size());
      helper.updateBalancingState(
          state,
          new Picker(activeList, error, startIndex, stickinessState));
    }

    /**
     * Filters out non-ready subchannels.
     */
    private static List<Subchannel> filterNonFailingSubchannels(
        Collection<Subchannel> subchannels) {
      List<Subchannel> readySubchannels = new ArrayList<Subchannel>(subchannels.size());
      for (Subchannel subchannel : subchannels) {
        if (getSubchannelStateInfoRef(subchannel).value.getState() == READY) {
          readySubchannels.add(subchannel);
        }
      }
      return readySubchannels;
    }

    /**
     * Converts list of {@link EquivalentAddressGroup} to {@link EquivalentAddressGroup} set and
     * remove all attributes.
     */
    private static Set<EquivalentAddressGroup> stripAttrs(List<EquivalentAddressGroup> groupList) {
      Set<EquivalentAddressGroup> addrs = new HashSet<EquivalentAddressGroup>(groupList.size());
      for (EquivalentAddressGroup group : groupList) {
        addrs.add(new EquivalentAddressGroup(group.getAddresses()));
      }
      return addrs;
    }

    /**
     * If all subchannels are TRANSIENT_FAILURE, return the Status associated with an arbitrary
     * subchannel otherwise, return null.
     */
    @Nullable
    private Status getAggregatedError() {
      Status status = null;
      for (Subchannel subchannel : getSubchannels()) {
        ConnectivityStateInfo stateInfo = getSubchannelStateInfoRef(subchannel).value;
        if (stateInfo.getState() != TRANSIENT_FAILURE) {
          return null;
        }
        status = stateInfo.getStatus();
      }
      return status;
    }

    private ConnectivityState getAggregatedState() {
      Set<ConnectivityState> states = EnumSet.noneOf(ConnectivityState.class);
      for (Subchannel subchannel : getSubchannels()) {
        states.add(getSubchannelStateInfoRef(subchannel).value.getState());
      }
      if (states.contains(READY)) {
        return READY;
      }
      if (states.contains(CONNECTING)) {
        return CONNECTING;
      }
      if (states.contains(IDLE)) {
        // This subchannel IDLE is not because of channel IDLE_TIMEOUT, in which case LB is already
        // shutdown.
        // RRLB will request connection immediately on subchannel IDLE.
        return CONNECTING;
      }
      return TRANSIENT_FAILURE;
    }

    @VisibleForTesting
    Collection<Subchannel> getSubchannels() {
      return subchannels.values();
    }

    private static Ref<ConnectivityStateInfo> getSubchannelStateInfoRef(
        Subchannel subchannel) {
      return checkNotNull(subchannel.getAttributes().get(STATE_INFO), "STATE_INFO");
    }

    private static <T> Set<T> setsDifference(Set<T> a, Set<T> b) {
      Set<T> aCopy = new HashSet<T>(a);
      aCopy.removeAll(b);
      return aCopy;
    }

    Map<String, Ref<Subchannel>> getStickinessMapForTest() {
      if (stickinessState == null) {
        return null;
      }
      return stickinessState.stickinessMap;
    }

    /**
     * Holds stickiness related states: The stickiness key, a registry mapping stickiness values to
     * the associated Subchannel Ref, and a map from Subchannel to Subchannel Ref.
     */
    private static final class StickinessState {
      static final int MAX_ENTRIES = 1000;

      final Key<String> key;
      final ConcurrentMap<String, Ref<Subchannel>> stickinessMap =
          new ConcurrentHashMap<String, Ref<Subchannel>>();

      final Queue<String> evictionQueue = new ConcurrentLinkedQueue<String>();

      StickinessState(@Nonnull String stickinessKey) {
        this.key = Key.of(stickinessKey, Metadata.ASCII_STRING_MARSHALLER);
      }

      /**
       * Returns the subchannel associated to the stickiness value if available in both the
       * registry and the round robin list, otherwise associates the given subchannel with the
       * stickiness key in the registry and returns the given subchannel.
       */
      @Nonnull
      Subchannel maybeRegister(
          String stickinessValue, @Nonnull Subchannel subchannel, List<Subchannel> rrList) {
        final Ref<Subchannel> newSubchannelRef = subchannel.getAttributes().get(STICKY_REF);
        while (true) {
          Ref<Subchannel> existingSubchannelRef =
              stickinessMap.putIfAbsent(stickinessValue, newSubchannelRef);
          if (existingSubchannelRef == null) {
            // new entry
            addToEvictionQueue(stickinessValue);
            return subchannel;
          } else {
            // existing entry
            Subchannel existingSubchannel = existingSubchannelRef.value;
            if (existingSubchannel != null && rrList.contains(existingSubchannel)) {
              return existingSubchannel;
            }
          }
          // existingSubchannelRef is not null but no longer valid, replace it
          if (stickinessMap.replace(stickinessValue, existingSubchannelRef, newSubchannelRef)) {
            return subchannel;
          }
          // another thread concurrently removed or updated the entry, try again
        }
      }

      private void addToEvictionQueue(String value) {
        String oldValue;
        while (stickinessMap.size() >= MAX_ENTRIES && (oldValue = evictionQueue.poll()) != null) {
          stickinessMap.remove(oldValue);
        }
        evictionQueue.add(value);
      }

      /**
       * Unregister the subchannel from StickinessState.
       */
      void remove(Subchannel subchannel) {
        subchannel.getAttributes().get(STICKY_REF).value = null;
      }

      /**
       * Gets the subchannel associated with the stickiness value if there is.
       */
      @Nullable
      Subchannel getSubchannel(String stickinessValue) {
        Ref<Subchannel> subchannelRef = stickinessMap.get(stickinessValue);
        if (subchannelRef != null) {
          return subchannelRef.value;
        }
        return null;
      }
    }
  }

  @VisibleForTesting
  static final class Picker extends SubchannelPicker {
    private static final AtomicIntegerFieldUpdater<Picker> indexUpdater =
        AtomicIntegerFieldUpdater.newUpdater(Picker.class, "index");

    @Nullable
    private final Status status;
    private final List<Subchannel> list;
    @Nullable
    private final RoundRobinLoadBalancer.StickinessState stickinessState;
    @SuppressWarnings("unused")
    private volatile int index;

    Picker(
        List<Subchannel> list, @Nullable Status status, int startIndex,
        @Nullable RoundRobinLoadBalancer.StickinessState stickinessState) {
      this.list = list;
      this.status = status;
      this.stickinessState = stickinessState;
      this.index = startIndex - 1;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      if (list.size() > 0) {
        Subchannel subchannel = null;
        if (stickinessState != null) {
          String stickinessValue = args.getHeaders().get(stickinessState.key);
          if (stickinessValue != null) {
            subchannel = stickinessState.getSubchannel(stickinessValue);
            if (subchannel == null || !list.contains(subchannel)) {
              subchannel = stickinessState.maybeRegister(stickinessValue, nextSubchannel(), list);
            }
          }
        }

        return PickResult.withSubchannel(subchannel != null ? subchannel : nextSubchannel());
      }

      if (status != null) {
        return PickResult.withError(status);
      }

      return PickResult.withNoResult();
    }

    private Subchannel nextSubchannel() {
      int size = list.size();
      if (size == 0) {
        throw new NoSuchElementException();
      }

      int i = indexUpdater.incrementAndGet(this);
      if (i >= size) {
        int oldi = i;
        i %= size;
        indexUpdater.compareAndSet(this, oldi, i);
      }
      return list.get(i);
    }

    @VisibleForTesting
    List<Subchannel> getList() {
      return list;
    }

    @VisibleForTesting
    Status getStatus() {
      return status;
    }
  }
}
