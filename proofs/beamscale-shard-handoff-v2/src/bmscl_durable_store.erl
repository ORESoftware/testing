-module(bmscl_durable_store).

-export_type([
    identity/0,
    owner_scope/0,
    owner_epoch/0,
    version/0,
    loaded/0,
    claim_result/0,
    commit_result/0,
    handoff_commit_result/0,
    handoff_cutover_result/0
]).

-type identity() :: #{
    tenant_id := binary(),
    application_id := binary(),
    namespace := binary(),
    object_key := binary()
}.

-type owner_scope() :: #{
    tenant_id := binary(),
    application_id := binary(),
    namespace := binary(),
    virtual_shard := non_neg_integer()
}.

-type owner_epoch() :: pos_integer().
-type version() :: non_neg_integer().

-type loaded() :: #{
    version := version(),
    state := binary()
}.

-type claim_result() ::
    {ok, owner_epoch()}
    | {error, term()}.

-type commit_result() ::
    {ok, version()}
    | {error, {stale_version, version()}}
    | {error, {stale_owner_epoch, owner_epoch()}}
    | {error, term()}.

-type handoff_commit_result() ::
    {ok, version()}
    | {error, {stale_handoff_version, version()}}
    | {error, term()}.

-type handoff_cutover_result() ::
    {ok, #{version := version(), owner_epoch := owner_epoch()}}
    | {error, {stale_handoff_version, version()}}
    | {error, term()}.

-callback load(identity(), owner_scope()) ->
    {ok, not_found}
    | {ok, loaded()}
    | {error, term()}.

-callback claim_owner(owner_scope()) -> claim_result().

-callback current_owner_epoch(owner_scope()) ->
    {ok, not_found}
    | {ok, owner_epoch()}
    | {error, term()}.

-callback commit(
    identity(),
    version(),
    owner_scope(),
    owner_epoch(),
    binary()
) -> commit_result().

-callback load_handoff(owner_scope()) ->
    {ok, not_found}
    | {ok, loaded()}
    | {error, term()}.

-callback commit_handoff(
    owner_scope(),
    version(),
    binary()
) -> handoff_commit_result().

-callback cutover_handoff(
    owner_scope(),
    version(),
    binary()
) -> handoff_cutover_result().
