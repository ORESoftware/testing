-module(bmscl_shard_rebalancer).

-export([
    begin_handoff/5,
    record_progress/3,
    cutover/2,
    mark_retired/2,
    load/1,
    current_owner_epoch/1
]).

-spec begin_handoff(map(), pos_integer(), [binary()], [binary()], non_neg_integer()) ->
    {ok, map()} | {error, term()}.
begin_handoff(Scope, PlacementEpoch, OldReplicas, TargetReplicas, BarrierPosition) ->
    case bmscl_shard_handoff_store:load(Scope) of
        {ok, not_found} ->
            persist_new_handoff(
              Scope,
              0,
              PlacementEpoch,
              OldReplicas,
              TargetReplicas,
              BarrierPosition);
        {ok, #{version := Version, state := Previous}} ->
            begin_after_previous(
              Scope,
              Version,
              Previous,
              PlacementEpoch,
              OldReplicas,
              TargetReplicas,
              BarrierPosition);
        {error, Reason} ->
            {error, Reason}
    end.

-spec record_progress(map(), binary(), non_neg_integer()) ->
    {ok, map()} | {error, term()}.
record_progress(Scope, Replica, Position) ->
    case require_loaded(Scope) of
        {ok, Version, State0} ->
            case bmscl_shard_handoff:record_progress(State0, Replica, Position) of
                {ok, State1} ->
                    bmscl_shard_handoff_store:commit(Version, State1);
                {error, Reason} ->
                    {error, Reason}
            end;
        {error, Reason} ->
            {error, Reason}
    end.

-spec cutover(map(), pos_integer()) -> {ok, map()} | {error, term()}.
cutover(Scope, NextPlacementEpoch) ->
    case require_loaded(Scope) of
        {ok, Version, State0} ->
            case bmscl_shard_handoff:advance_placement_epoch(
                   State0,
                   NextPlacementEpoch) of
                {ok, State1} ->
                    bmscl_shard_handoff_store:cutover(Version, State1);
                {error, Reason} ->
                    {error, Reason}
            end;
        {error, Reason} ->
            {error, Reason}
    end.

-spec mark_retired(map(), binary()) -> {ok, map()} | {error, term()}.
mark_retired(Scope, Replica) ->
    case require_loaded(Scope) of
        {ok, Version, State0} ->
            case bmscl_shard_handoff:mark_retired(State0, Replica) of
                {ok, State1} ->
                    bmscl_shard_handoff_store:commit(Version, State1);
                {error, Reason} ->
                    {error, Reason}
            end;
        {error, Reason} ->
            {error, Reason}
    end.

-spec load(map()) -> {ok, not_found | map()} | {error, term()}.
load(Scope) ->
    bmscl_shard_handoff_store:load(Scope).

-spec current_owner_epoch(map()) -> {ok, not_found | pos_integer()} | {error, term()}.
current_owner_epoch(Scope) ->
    bmscl_shard_handoff_store:current_owner_epoch(Scope).

begin_after_previous(Scope, Version, Previous, PlacementEpoch,
                     OldReplicas, TargetReplicas, BarrierPosition) ->
    case maps:get(phase, Previous, undefined) of
        complete ->
            PreviousEpoch = maps:get(placement_epoch, Previous),
            PreviousActive = maps:get(target_replicas, Previous),
            NormalizedOld = lists:usort(OldReplicas),
            case {PlacementEpoch > PreviousEpoch,
                  NormalizedOld =:= lists:sort(PreviousActive)} of
                {true, true} ->
                    persist_new_handoff(
                      Scope,
                      Version,
                      PlacementEpoch,
                      OldReplicas,
                      TargetReplicas,
                      BarrierPosition);
                {false, _} ->
                    {error, {non_monotonic_placement_epoch, PreviousEpoch}};
                {_, false} ->
                    {error, {stale_old_replica_set, PreviousActive}}
            end;
        Phase ->
            {error, {handoff_in_progress, Phase}}
    end.

persist_new_handoff(Scope, ExpectedVersion, PlacementEpoch,
                    OldReplicas, TargetReplicas, BarrierPosition) ->
    case bmscl_shard_handoff:begin_handoff(
           Scope,
           PlacementEpoch,
           OldReplicas,
           TargetReplicas,
           BarrierPosition) of
        {ok, State} ->
            bmscl_shard_handoff_store:commit(ExpectedVersion, State);
        {error, Reason} ->
            {error, Reason}
    end.

require_loaded(Scope) ->
    case bmscl_shard_handoff_store:load(Scope) of
        {ok, not_found} ->
            {error, handoff_not_found};
        {ok, #{version := Version, state := State}} ->
            {ok, Version, State};
        {error, Reason} ->
            {error, Reason}
    end.

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

persistent_cutover_fences_old_owner_test() ->
    PreviousStore = application:get_env(bmscl_supervisor, durable_store_module),
    application:set_env(
      bmscl_supervisor,
      durable_store_module,
      bmscl_durable_test_store),
    try
        ok = bmscl_durable_test_store:reset(),
        Scope = test_scope(),
        Identity = #{tenant_id => <<"tenant-a">>,
                     application_id => <<"app-a">>,
                     namespace => <<"orders">>,
                     object_key => <<"order-9">>},
        {ok, OldOwnerEpoch} = bmscl_durable_test_store:claim_owner(Scope),
        {ok, #{version := 1}} = begin_handoff(
            Scope,
            71,
            [<<"a">>, <<"b">>, <<"c">>],
            [<<"b">>, <<"c">>, <<"d">>],
            10044),
        {ok, #{version := 2}} = record_progress(Scope, <<"d">>, 10044),
        {ok, #{version := 3,
               owner_epoch := NewOwnerEpoch,
               state := CutoverState}} = cutover(Scope, 72),
        ?assert(NewOwnerEpoch > OldOwnerEpoch),
        ?assertEqual(cutover, maps:get(phase, CutoverState)),
        ?assertEqual(
           {error, {stale_owner_epoch, NewOwnerEpoch}},
           bmscl_durable_test_store:commit(
             Identity,
             0,
             Scope,
             OldOwnerEpoch,
             <<"stale-source-write">>)),
        {ok, #{version := 4, state := CompleteState}} = mark_retired(Scope, <<"a">>),
        ?assertEqual(complete, maps:get(phase, CompleteState)),
        ?assertEqual({ok, NewOwnerEpoch}, current_owner_epoch(Scope))
    after
        restore_store_env(PreviousStore)
    end.

cutover_requires_caught_up_target_test() ->
    PreviousStore = application:get_env(bmscl_supervisor, durable_store_module),
    application:set_env(
      bmscl_supervisor,
      durable_store_module,
      bmscl_durable_test_store),
    try
        ok = bmscl_durable_test_store:reset(),
        Scope = test_scope(),
        {ok, _} = bmscl_durable_test_store:claim_owner(Scope),
        {ok, _} = begin_handoff(
            Scope,
            4,
            [<<"a">>, <<"b">>, <<"c">>],
            [<<"b">>, <<"c">>, <<"d">>],
            50),
        ?assertEqual({error, target_not_caught_up}, cutover(Scope, 5)),
        ?assertEqual({ok, 1}, current_owner_epoch(Scope))
    after
        restore_store_env(PreviousStore)
    end.

test_scope() ->
    #{tenant_id => <<"tenant-a">>,
      application_id => <<"app-a">>,
      namespace => <<"orders">>,
      virtual_shard => 3816}.

restore_store_env(undefined) ->
    application:unset_env(bmscl_supervisor, durable_store_module);
restore_store_env({ok, Value}) ->
    application:set_env(bmscl_supervisor, durable_store_module, Value).

-endif.
