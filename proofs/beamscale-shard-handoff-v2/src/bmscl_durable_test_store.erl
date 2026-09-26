-module(bmscl_durable_test_store).
-behaviour(bmscl_durable_store).

-export([
    reset/0,
    load/2,
    claim_owner/1,
    current_owner_epoch/1,
    commit/5,
    load_handoff/1,
    commit_handoff/3,
    cutover_handoff/3
]).

-define(TABLE, bmscl_durable_test_store_table).

reset() ->
    case ets:whereis(?TABLE) of
        undefined ->
            ok;
        _ ->
            ets:delete(?TABLE)
    end,
    _ = ets:new(
          ?TABLE,
          [named_table, public, set,
           {read_concurrency, true}, {write_concurrency, true}]),
    ok.

load(Identity, _OwnerScope) ->
    Tab = ensure_table(),
    case ets:lookup(Tab, {object, Identity}) of
        [] ->
            {ok, not_found};
        [{{object, Identity}, Version, State}] ->
            {ok, #{version => Version, state => State}}
    end.

claim_owner(OwnerScope) ->
    Tab = ensure_table(),
    Epoch = ets:update_counter(
              Tab,
              {owner, OwnerScope},
              {2, 1},
              {{owner, OwnerScope}, 0}),
    {ok, Epoch}.

current_owner_epoch(OwnerScope) ->
    Tab = ensure_table(),
    case ets:lookup(Tab, {owner, OwnerScope}) of
        [] ->
            {ok, not_found};
        [{{owner, OwnerScope}, Epoch}] ->
            {ok, Epoch}
    end.

commit(Identity, ExpectedVersion, OwnerScope, OwnerEpoch, Payload)
  when is_integer(ExpectedVersion), ExpectedVersion >= 0,
       is_integer(OwnerEpoch), OwnerEpoch > 0,
       is_binary(Payload) ->
    Tab = ensure_table(),
    case ets:lookup(Tab, {owner, OwnerScope}) of
        [] ->
            {error, owner_missing};
        [{{owner, OwnerScope}, CurrentOwner}] when CurrentOwner =/= OwnerEpoch ->
            {error, {stale_owner_epoch, CurrentOwner}};
        [{{owner, OwnerScope}, OwnerEpoch}] ->
            CurrentVersion = object_version(Tab, Identity),
            case CurrentVersion =:= ExpectedVersion of
                false ->
                    {error, {stale_version, CurrentVersion}};
                true ->
                    Next = CurrentVersion + 1,
                    true = ets:insert(Tab, {{object, Identity}, Next, Payload}),
                    {ok, Next}
            end
    end;
commit(_, _, _, _, _) ->
    {error, invalid_durable_commit}.

load_handoff(OwnerScope) ->
    Tab = ensure_table(),
    case ets:lookup(Tab, {handoff, OwnerScope}) of
        [] ->
            {ok, not_found};
        [{{handoff, OwnerScope}, Version, Payload}] ->
            {ok, #{version => Version, state => Payload}}
    end.

commit_handoff(OwnerScope, ExpectedVersion, Payload)
  when is_integer(ExpectedVersion), ExpectedVersion >= 0,
       is_binary(Payload) ->
    Tab = ensure_table(),
    global:trans(
      {?MODULE, OwnerScope},
      fun() ->
          CurrentVersion = handoff_version(Tab, OwnerScope),
          case CurrentVersion =:= ExpectedVersion of
              false ->
                  {error, {stale_handoff_version, CurrentVersion}};
              true ->
                  Next = CurrentVersion + 1,
                  true = ets:insert(
                           Tab,
                           {{handoff, OwnerScope}, Next, Payload}),
                  {ok, Next}
          end
      end);
commit_handoff(_, _, _) ->
    {error, invalid_handoff_commit}.

cutover_handoff(OwnerScope, ExpectedVersion, Payload)
  when is_integer(ExpectedVersion), ExpectedVersion >= 0,
       is_binary(Payload) ->
    Tab = ensure_table(),
    global:trans(
      {?MODULE, OwnerScope},
      fun() ->
          CurrentVersion = handoff_version(Tab, OwnerScope),
          case CurrentVersion =:= ExpectedVersion of
              false ->
                  {error, {stale_handoff_version, CurrentVersion}};
              true ->
                  Next = CurrentVersion + 1,
                  Epoch = ets:update_counter(
                            Tab,
                            {owner, OwnerScope},
                            {2, 1},
                            {{owner, OwnerScope}, 0}),
                  true = ets:insert(
                           Tab,
                           {{handoff, OwnerScope}, Next, Payload}),
                  {ok, #{version => Next, owner_epoch => Epoch}}
          end
      end);
cutover_handoff(_, _, _) ->
    {error, invalid_handoff_cutover}.

object_version(Tab, Identity) ->
    case ets:lookup(Tab, {object, Identity}) of
        [] ->
            0;
        [{{object, Identity}, Version, _}] ->
            Version
    end.

handoff_version(Tab, OwnerScope) ->
    case ets:lookup(Tab, {handoff, OwnerScope}) of
        [] ->
            0;
        [{{handoff, OwnerScope}, Version, _}] ->
            Version
    end.

ensure_table() ->
    case ets:whereis(?TABLE) of
        undefined ->
            try ets:new(
                  ?TABLE,
                  [named_table, public, set,
                   {read_concurrency, true}, {write_concurrency, true}]) of
                Tab ->
                    Tab
            catch
                error:badarg ->
                    ?TABLE
            end;
        Tab ->
            Tab
    end.
