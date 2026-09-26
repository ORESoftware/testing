-module(bmscl_shard_placement).

%% Deterministic virtual-shard mapping and weighted rendezvous placement.
%%
%% Layout version 1 deliberately preserves the hash contract already shipped by
%% bmscl_durable_shard. Machine membership never participates in the object ->
%% virtual-shard hash. Physical membership is a second mapping performed by HRW.
%%
%% Placement epochs are also deliberately excluded from the HRW score. If the
%% eligible candidate set and weights are unchanged, incrementing a control-plane
%% epoch must not reshuffle every shard.

-export([
    virtual_shard/3,
    rank_candidates/2,
    select_replicas/3,
    plan/4
]).

-define(MAX_VIRTUAL_SHARDS, 16777216).
-define(MAX_CANDIDATE_WEIGHT, 1000000).
-define(HRW_DENOMINATOR, 9007199254740993).

-type identity() :: #{
    tenant_id := binary(),
    application_id := binary(),
    namespace := binary(),
    object_key := binary()
}.

-type shard_scope() :: #{
    tenant_id := binary(),
    application_id := binary(),
    namespace := binary(),
    virtual_shard := non_neg_integer()
}.

-type candidate() :: #{
    id := binary(),
    weight := pos_integer(),
    failure_domain := binary(),
    eligible => boolean()
}.

-export_type([identity/0, shard_scope/0, candidate/0]).

-spec virtual_shard(identity(), pos_integer(), pos_integer()) ->
    {ok, non_neg_integer()} | {error, term()}.
virtual_shard(Identity, LayoutVersion, ShardCount)
  when is_map(Identity),
       is_integer(LayoutVersion), LayoutVersion > 0,
       is_integer(ShardCount), ShardCount > 0,
       ShardCount =< ?MAX_VIRTUAL_SHARDS ->
    case normalize_identity(Identity) of
        {ok, Normalized} ->
            case layout_bytes(LayoutVersion, Normalized) of
                {ok, Bytes} ->
                    Digest = crypto:hash(sha256, Bytes),
                    <<Prefix:64/unsigned-big, _/binary>> = Digest,
                    {ok, Prefix rem ShardCount};
                {error, Reason} ->
                    {error, Reason}
            end;
        {error, Reason} ->
            {error, Reason}
    end;
virtual_shard(_, _, _) ->
    {error, invalid_virtual_shard_layout}.

-spec rank_candidates(shard_scope(), [candidate()]) ->
    {ok, [candidate()]} | {error, term()}.
rank_candidates(ShardScope, Candidates) when is_map(ShardScope), is_list(Candidates) ->
    case {normalize_scope(ShardScope), normalize_candidates(Candidates)} of
        {{ok, Scope}, {ok, Normalized}} ->
            Eligible = [Candidate || Candidate <- Normalized,
                                     maps:get(eligible, Candidate, true)],
            Scored = [
                {hrw_score(Scope, Candidate), maps:get(id, Candidate), Candidate}
                || Candidate <- Eligible
            ],
            Sorted = lists:sort(fun score_before/2, Scored),
            {ok, [Candidate || {_Score, _Id, Candidate} <- Sorted]};
        {{error, Reason}, _} ->
            {error, Reason};
        {_, {error, Reason}} ->
            {error, Reason}
    end;
rank_candidates(_, _) ->
    {error, invalid_placement_candidates}.

-spec select_replicas(shard_scope(), [candidate()], pos_integer()) ->
    {ok, [binary()]} | {error, term()}.
select_replicas(ShardScope, Candidates, ReplicaCount)
  when is_integer(ReplicaCount), ReplicaCount > 0 ->
    case rank_candidates(ShardScope, Candidates) of
        {ok, Ranked} when length(Ranked) >= ReplicaCount ->
            Selected = choose_failure_domains(Ranked, ReplicaCount),
            {ok, [maps:get(id, Candidate) || Candidate <- Selected]};
        {ok, Ranked} ->
            {error, {insufficient_eligible_replicas, length(Ranked), ReplicaCount}};
        {error, Reason} ->
            {error, Reason}
    end;
select_replicas(_, _, _) ->
    {error, invalid_replica_count}.

-spec plan(shard_scope(), [candidate()], pos_integer(), pos_integer()) ->
    {ok, map()} | {error, term()}.
plan(ShardScope, Candidates, ReplicaCount, PlacementEpoch)
  when is_integer(PlacementEpoch), PlacementEpoch > 0 ->
    case select_replicas(ShardScope, Candidates, ReplicaCount) of
        {ok, Replicas} ->
            {ok, #{
                shard_scope => ShardScope,
                placement_epoch => PlacementEpoch,
                target_replicas => Replicas,
                replica_count => ReplicaCount
            }};
        {error, Reason} ->
            {error, Reason}
    end;
plan(_, _, _, _) ->
    {error, invalid_placement_epoch}.

layout_bytes(1, #{tenant_id := Tenant,
                  application_id := Application,
                  namespace := Namespace,
                  object_key := ObjectKey}) ->
    IdentityTuple = {Tenant, Application, Namespace, ObjectKey},
    {ok, term_to_binary(
           {<<"bmscl-durable-v1">>, IdentityTuple},
           [deterministic])};
layout_bytes(2, #{tenant_id := Tenant,
                  application_id := Application,
                  namespace := Namespace,
                  object_key := ObjectKey}) ->
    {ok, iolist_to_binary([
        <<"bmscl-durable-layout-v2">>,
        frame(Tenant),
        frame(Application),
        frame(Namespace),
        frame(ObjectKey)
    ])};
layout_bytes(LayoutVersion, _) ->
    {error, {unsupported_layout_version, LayoutVersion}}.

frame(Value) ->
    <<(byte_size(Value)):32/unsigned-big, Value/binary>>.

normalize_identity(#{tenant_id := Tenant,
                     application_id := Application,
                     namespace := Namespace,
                     object_key := ObjectKey}) ->
    case {bounded_binary(Tenant, 1024),
          bounded_binary(Application, 1024),
          bounded_binary(Namespace, 128),
          bounded_binary(ObjectKey, 4096)} of
        {{ok, T}, {ok, A}, {ok, N}, {ok, O}} ->
            {ok, #{tenant_id => T,
                   application_id => A,
                   namespace => N,
                   object_key => O}};
        _ ->
            {error, invalid_durable_identity}
    end;
normalize_identity(_) ->
    {error, invalid_durable_identity}.

normalize_scope(#{tenant_id := Tenant,
                  application_id := Application,
                  namespace := Namespace,
                  virtual_shard := VirtualShard})
  when is_integer(VirtualShard), VirtualShard >= 0 ->
    case {bounded_binary(Tenant, 1024),
          bounded_binary(Application, 1024),
          bounded_binary(Namespace, 128)} of
        {{ok, T}, {ok, A}, {ok, N}} ->
            {ok, #{tenant_id => T,
                   application_id => A,
                   namespace => N,
                   virtual_shard => VirtualShard}};
        _ ->
            {error, invalid_shard_scope}
    end;
normalize_scope(_) ->
    {error, invalid_shard_scope}.

normalize_candidates(Candidates) ->
    normalize_candidates(Candidates, [], []).

normalize_candidates([], Acc, _Seen) ->
    {ok, lists:reverse(Acc)};
normalize_candidates([Candidate | Rest], Acc, Seen) when is_map(Candidate) ->
    case normalize_candidate(Candidate) of
        {ok, Normalized = #{id := Id}} ->
            case lists:member(Id, Seen) of
                true ->
                    {error, {duplicate_candidate_id, Id}};
                false ->
                    normalize_candidates(Rest, [Normalized | Acc], [Id | Seen])
            end;
        {error, Reason} ->
            {error, Reason}
    end;
normalize_candidates(_, _, _) ->
    {error, invalid_placement_candidates}.

normalize_candidate(#{id := Id,
                      weight := Weight,
                      failure_domain := FailureDomain} = Candidate)
  when is_integer(Weight), Weight > 0, Weight =< ?MAX_CANDIDATE_WEIGHT ->
    Eligible = maps:get(eligible, Candidate, true),
    case {bounded_binary(Id, 256), bounded_binary(FailureDomain, 256), Eligible} of
        {{ok, I}, {ok, D}, E} when is_boolean(E) ->
            {ok, #{id => I,
                   weight => Weight,
                   failure_domain => D,
                   eligible => E}};
        _ ->
            {error, invalid_placement_candidate}
    end;
normalize_candidate(_) ->
    {error, invalid_placement_candidate}.

bounded_binary(Value, Max)
  when is_binary(Value), byte_size(Value) > 0, byte_size(Value) =< Max ->
    case binary:match(Value, <<0>>) of
        nomatch ->
            {ok, Value};
        _ ->
            {error, invalid_binary}
    end;
bounded_binary(_, _) ->
    {error, invalid_binary}.

hrw_score(Scope, #{id := Id, weight := Weight}) ->
    Digest = crypto:hash(
               sha256,
               term_to_binary(
                 {<<"bmscl-weighted-hrw-v1">>, canonical_scope(Scope), Id},
                 [deterministic])),
    <<Raw:64/unsigned-big, _/binary>> = Digest,
    Mantissa = (Raw band ((1 bsl 53) - 1)) + 1,
    Unit = Mantissa / ?HRW_DENOMINATOR,
    -math:log(Unit) / Weight.

canonical_scope(#{tenant_id := Tenant,
                  application_id := Application,
                  namespace := Namespace,
                  virtual_shard := VirtualShard}) ->
    {Tenant, Application, Namespace, VirtualShard}.

score_before({ScoreA, _IdA, _}, {ScoreB, _IdB, _}) when ScoreA < ScoreB ->
    true;
score_before({ScoreA, _IdA, _}, {ScoreB, _IdB, _}) when ScoreA > ScoreB ->
    false;
score_before({_ScoreA, IdA, _}, {_ScoreB, IdB, _}) ->
    IdA < IdB.

choose_failure_domains(Ranked, ReplicaCount) ->
    {Diverse, _SeenDomains} = take_distinct_domains(Ranked, ReplicaCount, [], []),
    case length(Diverse) of
        ReplicaCount ->
            Diverse;
        _ ->
            PickedIds = [maps:get(id, Candidate) || Candidate <- Diverse],
            Remaining = [Candidate || Candidate <- Ranked,
                                      not lists:member(maps:get(id, Candidate), PickedIds)],
            Need = ReplicaCount - length(Diverse),
            Diverse ++ lists:sublist(Remaining, Need)
    end.

take_distinct_domains(_Ranked, 0, Acc, SeenDomains) ->
    {lists:reverse(Acc), SeenDomains};
take_distinct_domains([], _Need, Acc, SeenDomains) ->
    {lists:reverse(Acc), SeenDomains};
take_distinct_domains([Candidate | Rest], Need, Acc, SeenDomains) ->
    Domain = maps:get(failure_domain, Candidate),
    case lists:member(Domain, SeenDomains) of
        true ->
            take_distinct_domains(Rest, Need, Acc, SeenDomains);
        false ->
            take_distinct_domains(
              Rest,
              Need - 1,
              [Candidate | Acc],
              [Domain | SeenDomains])
    end.

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

v1_virtual_shard_golden_vector_test() ->
    Identity = #{tenant_id => <<"tenant-a">>,
                 application_id => <<"app-a">>,
                 namespace => <<"orders">>,
                 object_key => <<"customer-42">>},
    ?assertEqual({ok, 3816}, virtual_shard(Identity, 1, 65536)).

adding_candidate_preserves_relative_hrw_order_test() ->
    Scope = test_scope(),
    A = candidate(<<"a">>, <<"rack-1">>),
    B = candidate(<<"b">>, <<"rack-2">>),
    C = candidate(<<"c">>, <<"rack-3">>),
    D = candidate(<<"d">>, <<"rack-4">>),
    {ok, Ranked0} = rank_candidates(Scope, [A, B, C]),
    {ok, Ranked1} = rank_candidates(Scope, [A, B, C, D]),
    Ids0 = [maps:get(id, Candidate) || Candidate <- Ranked0],
    Ids1 = [maps:get(id, Candidate) || Candidate <- Ranked1,
                                      maps:get(id, Candidate) =/= <<"d">>],
    ?assertEqual(Ids0, Ids1).

selection_prefers_failure_domain_diversity_test() ->
    Scope = test_scope(),
    Candidates = [
        candidate(<<"a">>, <<"rack-1">>),
        candidate(<<"b">>, <<"rack-1">>),
        candidate(<<"c">>, <<"rack-2">>),
        candidate(<<"d">>, <<"rack-3">>)
    ],
    {ok, Selected} = select_replicas(Scope, Candidates, 3),
    Domains = [
        maps:get(failure_domain, find_candidate(Id, Candidates))
        || Id <- Selected
    ],
    ?assertEqual(3, length(lists:usort(Domains))).

ineligible_candidate_is_never_selected_test() ->
    Scope = test_scope(),
    A = candidate(<<"a">>, <<"rack-1">>),
    B = (candidate(<<"b">>, <<"rack-2">>))#{eligible => false},
    C = candidate(<<"c">>, <<"rack-3">>),
    {ok, Selected} = select_replicas(Scope, [A, B, C], 2),
    ?assertEqual(false, lists:member(<<"b">>, Selected)).

candidate(Id, Domain) ->
    #{id => Id, weight => 100, failure_domain => Domain, eligible => true}.

test_scope() ->
    #{tenant_id => <<"tenant-a">>,
      application_id => <<"app-a">>,
      namespace => <<"orders">>,
      virtual_shard => 3816}.

find_candidate(Id, Candidates) ->
    hd([Candidate || Candidate <- Candidates, maps:get(id, Candidate) =:= Id]).

-endif.
