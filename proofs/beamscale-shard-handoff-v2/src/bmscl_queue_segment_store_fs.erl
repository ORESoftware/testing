-module(bmscl_queue_segment_store_fs).

-export([
    seal/3,
    recover/2,
    read_segment/3,
    partition_dir/2,
    sealed_filename/1
]).

-define(SEGMENT_SUFFIX, ".bmsg").
-define(TEMP_PREFIX, ".tmp-").
-define(QUARANTINE_DIR, "quarantine").

-spec seal(file:name_all(), binary(), [binary()]) -> {ok, map()} | {error, term()}.
seal(BaseDir, PartitionId, Frames)
  when is_binary(PartitionId), is_list(Frames) ->
    case bmscl_queue_segment:build(Frames) of
        {ok, Segment, Manifest0} ->
            with_partition_lock(
              BaseDir,
              PartitionId,
              fun(PartitionDir) -> seal_segment(PartitionDir, Segment, Manifest0) end);
        {error, Reason} ->
            {error, Reason}
    end;
seal(_, _, _) ->
    {error, invalid_segment_seal}.

-spec recover(file:name_all(), binary()) -> {ok, map()} | {error, term()}.
recover(BaseDir, PartitionId) when is_binary(PartitionId) ->
    with_partition_lock(
      BaseDir,
      PartitionId,
      fun(PartitionDir) -> recover_partition(PartitionDir) end);
recover(_, _) ->
    {error, invalid_partition_id}.

-spec read_segment(file:name_all(), binary(), pos_integer()) ->
    {ok, binary(), map()} | {error, term()}.
read_segment(BaseDir, PartitionId, BaseSequence)
  when is_binary(PartitionId), is_integer(BaseSequence), BaseSequence > 0 ->
    case recover(BaseDir, PartitionId) of
        {ok, #{segments := Segments}} ->
            Matches = [Manifest || Manifest <- Segments,
                                   maps:get(base_sequence, Manifest) =:= BaseSequence],
            case Matches of
                [Manifest] ->
                    Path = maps:get(path, Manifest),
                    case file:read_file(Path) of
                        {ok, Bytes} -> {ok, Bytes, Manifest};
                        {error, Reason} -> {error, {segment_read_failed, Reason}}
                    end;
                [] ->
                    {error, not_found};
                _ ->
                    {error, duplicate_segment_base_sequence}
            end;
        {error, Reason} ->
            {error, Reason}
    end;
read_segment(_, _, _) ->
    {error, invalid_segment_sequence}.

-spec partition_dir(file:name_all(), binary()) ->
    {ok, file:filename_all()} | {error, term()}.
partition_dir(BaseDir0, PartitionId)
  when is_binary(PartitionId), byte_size(PartitionId) > 0, byte_size(PartitionId) =< 4096 ->
    case normalize_base_dir(BaseDir0) of
        {ok, BaseDir} ->
            Digest = hex(crypto:hash(sha256, PartitionId)),
            {ok, filename:join([BaseDir, "partitions", binary_to_list(Digest)])};
        {error, Reason} ->
            {error, Reason}
    end;
partition_dir(_, _) ->
    {error, invalid_partition_id}.

-spec sealed_filename(map()) -> binary().
sealed_filename(Manifest) when is_map(Manifest) ->
    Base = maps:get(base_sequence, Manifest),
    Last = maps:get(last_sequence, Manifest),
    Digest = maps:get(digest, Manifest),
    iolist_to_binary([
        "segment-", pad_sequence(Base), "-", pad_sequence(Last), "-",
        hex(Digest), ?SEGMENT_SUFFIX
    ]).

seal_segment(PartitionDir, Segment, Manifest0) ->
    case ensure_directory(PartitionDir) of
        ok ->
            Filename = binary_to_list(sealed_filename(Manifest0)),
            FinalPath = filename:join(PartitionDir, Filename),
            case verify_existing(FinalPath, Manifest0) of
                {ok, ExistingManifest} ->
                    {ok, ExistingManifest#{path => FinalPath, durability => file_sync}};
                not_found ->
                    write_new_segment(PartitionDir, FinalPath, Segment, Manifest0);
                {error, Reason} ->
                    {error, Reason}
            end;
        {error, Reason} ->
            {error, Reason}
    end.

write_new_segment(PartitionDir, FinalPath, Segment, Manifest0) ->
    TempPath = temp_path(PartitionDir),
    case file:open(TempPath, [write, binary, raw, exclusive]) of
        {ok, IoDevice} ->
            case write_sync_close(IoDevice, Segment) of
                ok ->
                    case file:rename(TempPath, FinalPath) of
                        ok ->
                            case verify_existing(FinalPath, Manifest0) of
                                {ok, Manifest1} ->
                                    {ok, Manifest1#{path => FinalPath, durability => file_sync}};
                                {error, Reason} ->
                                    {error, {sealed_segment_verify_failed, Reason}};
                                not_found ->
                                    {error, sealed_segment_missing_after_rename}
                            end;
                        {error, eexist} ->
                            _ = safe_delete(TempPath),
                            verify_existing_after_race(FinalPath, Manifest0);
                        {error, Reason} ->
                            _ = safe_delete(TempPath),
                            {error, {segment_rename_failed, Reason}}
                    end;
                {error, Reason} ->
                    _ = safe_delete(TempPath),
                    {error, Reason}
            end;
        {error, Reason} ->
            {error, {segment_temp_open_failed, Reason}}
    end.

verify_existing_after_race(FinalPath, Manifest0) ->
    case verify_existing(FinalPath, Manifest0) of
        {ok, Manifest1} ->
            {ok, Manifest1#{path => FinalPath, durability => file_sync}};
        not_found ->
            {error, segment_rename_race_lost_but_target_missing};
        {error, Reason} ->
            {error, Reason}
    end.

write_sync_close(IoDevice, Segment) ->
    case file:write(IoDevice, Segment) of
        ok ->
            case file:sync(IoDevice) of
                ok ->
                    case file:close(IoDevice) of
                        ok -> ok;
                        {error, Reason} -> {error, {segment_close_failed, Reason}}
                    end;
                {error, Reason} ->
                    _ = file:close(IoDevice),
                    {error, {segment_sync_failed, Reason}}
            end;
        {error, Reason} ->
            _ = file:close(IoDevice),
            {error, {segment_write_failed, Reason}}
    end.

verify_existing(Path, ExpectedManifest) ->
    case file:read_file(Path) of
        {ok, Bytes} ->
            case bmscl_queue_segment:decode(Bytes) of
                {ok, _Frames, ActualManifest} ->
                    case manifest_identity(ActualManifest) =:= manifest_identity(ExpectedManifest) of
                        true -> {ok, ActualManifest};
                        false -> {error, {segment_identity_conflict, Path}}
                    end;
                {error, Reason} ->
                    {error, {existing_segment_corrupt, Path, Reason}}
            end;
        {error, enoent} ->
            not_found;
        {error, Reason} ->
            {error, {existing_segment_read_failed, Path, Reason}}
    end.

recover_partition(PartitionDir) ->
    case ensure_directory(PartitionDir) of
        ok ->
            QuarantineDir = filename:join(PartitionDir, ?QUARANTINE_DIR),
            case ensure_directory(QuarantineDir) of
                ok -> recover_partition_listing(PartitionDir, QuarantineDir);
                {error, Reason} -> {error, Reason}
            end;
        {error, Reason} ->
            {error, Reason}
    end.

recover_partition_listing(PartitionDir, QuarantineDir) ->
    case file:list_dir(PartitionDir) of
        {ok, Names} ->
            TempNames = [Name || Name <- Names, is_temp_name(Name)],
            TempQuarantine = quarantine_names(PartitionDir, QuarantineDir, TempNames, stale_temp),
            SealedNames = [Name || Name <- Names, is_sealed_name(Name)],
            case recover_sealed(PartitionDir, QuarantineDir, SealedNames, [], []) of
                {ok, Manifests0, CorruptQuarantine} ->
                    Manifests = lists:sort(fun manifest_before/2, Manifests0),
                    case validate_inventory(Manifests) of
                        ok -> {ok, inventory(Manifests, TempQuarantine ++ CorruptQuarantine)};
                        {error, Reason} -> {error, Reason}
                    end;
                {error, Reason} ->
                    {error, Reason}
            end;
        {error, Reason} ->
            {error, {segment_directory_list_failed, Reason}}
    end.

recover_sealed(_PartitionDir, _QuarantineDir, [], Manifests, Quarantined) ->
    {ok, Manifests, Quarantined};
recover_sealed(PartitionDir, QuarantineDir, [Name | Rest], Manifests, Quarantined) ->
    Path = filename:join(PartitionDir, Name),
    case file:read_file(Path) of
        {ok, Bytes} ->
            case bmscl_queue_segment:decode(Bytes) of
                {ok, _Frames, Manifest0} ->
                    ExpectedName = binary_to_list(sealed_filename(Manifest0)),
                    case Name =:= ExpectedName of
                        true ->
                            Manifest = Manifest0#{path => Path, durability => recovered},
                            recover_sealed(PartitionDir, QuarantineDir, Rest, [Manifest | Manifests], Quarantined);
                        false ->
                            Item = quarantine_one(Path, QuarantineDir, filename_mismatch),
                            recover_sealed(PartitionDir, QuarantineDir, Rest, Manifests, [Item | Quarantined])
                    end;
                {error, Reason} ->
                    Item = quarantine_one(Path, QuarantineDir, {segment_decode_failed, Reason}),
                    recover_sealed(PartitionDir, QuarantineDir, Rest, Manifests, [Item | Quarantined])
            end;
        {error, Reason} ->
            {error, {segment_read_failed, Path, Reason}}
    end.

validate_inventory([]) -> ok;
validate_inventory([_]) -> ok;
validate_inventory([Left, Right | Rest]) ->
    LeftLast = maps:get(last_sequence, Left),
    RightBase = maps:get(base_sequence, Right),
    case RightBase of
        Expected when Expected =:= LeftLast + 1 ->
            validate_inventory([Right | Rest]);
        Overlap when Overlap =< LeftLast ->
            {error, {overlapping_segments, maps:get(path, Left), maps:get(path, Right)}};
        Gap ->
            {error, {segment_gap, LeftLast + 1, Gap}}
    end.

inventory([], Quarantined) ->
    #{segments => [], earliest_sequence => undefined, last_sequence => 0,
      max_term => 0, total_bytes => 0, quarantined => Quarantined};
inventory(Manifests, Quarantined) ->
    First = hd(Manifests),
    Last = lists:last(Manifests),
    #{segments => Manifests,
      earliest_sequence => maps:get(base_sequence, First),
      last_sequence => maps:get(last_sequence, Last),
      max_term => lists:max([maps:get(max_term, Manifest) || Manifest <- Manifests]),
      total_bytes => lists:sum([maps:get(byte_size, Manifest) || Manifest <- Manifests]),
      quarantined => Quarantined}.

quarantine_names(PartitionDir, QuarantineDir, Names, Reason) ->
    [quarantine_one(filename:join(PartitionDir, Name), QuarantineDir, Reason) || Name <- Names].

quarantine_one(Path, QuarantineDir, Reason) ->
    Base = filename:basename(Path),
    Suffix = integer_to_list(erlang:unique_integer([positive, monotonic])),
    Destination = filename:join(QuarantineDir, Base ++ "." ++ Suffix ++ ".bad"),
    case file:rename(Path, Destination) of
        ok ->
            #{source => Path, destination => Destination, reason => Reason};
        {error, RenameReason} ->
            #{source => Path, destination => undefined, reason => Reason,
              quarantine_error => RenameReason}
    end.

with_partition_lock(BaseDir, PartitionId, Fun) ->
    case partition_dir(BaseDir, PartitionId) of
        {ok, PartitionDir} ->
            case global:trans({?MODULE, PartitionDir}, fun() -> Fun(PartitionDir) end) of
                aborted -> {error, segment_store_lock_aborted};
                Result -> Result
            end;
        {error, Reason} ->
            {error, Reason}
    end.

ensure_directory(Path) ->
    Placeholder = filename:join(Path, ".ensure"),
    case filelib:ensure_dir(Placeholder) of
        ok -> ok;
        {error, Reason} -> {error, {segment_directory_create_failed, Path, Reason}}
    end.

normalize_base_dir(Value) when is_binary(Value), byte_size(Value) > 0 ->
    {ok, binary_to_list(Value)};
normalize_base_dir(Value) when is_list(Value), Value =/= [] ->
    {ok, Value};
normalize_base_dir(_) ->
    {error, invalid_segment_base_dir}.

temp_path(PartitionDir) ->
    Unique = integer_to_list(erlang:unique_integer([positive, monotonic])),
    filename:join(PartitionDir, ?TEMP_PREFIX ++ Unique ++ ".bmsg").

is_temp_name(Name) when is_list(Name) -> lists:prefix(?TEMP_PREFIX, Name);
is_temp_name(_) -> false.

is_sealed_name(Name) when is_list(Name) ->
    lists:prefix("segment-", Name) andalso filename:extension(Name) =:= ?SEGMENT_SUFFIX;
is_sealed_name(_) -> false.

manifest_before(Left, Right) ->
    maps:get(base_sequence, Left) < maps:get(base_sequence, Right).

manifest_identity(Manifest) ->
    {maps:get(base_sequence, Manifest), maps:get(last_sequence, Manifest),
     maps:get(record_count, Manifest), maps:get(digest, Manifest)}.

pad_sequence(Value) when is_integer(Value), Value >= 0 ->
    iolist_to_binary(io_lib:format("~20..0B", [Value])).

hex(Bytes) when is_binary(Bytes) ->
    iolist_to_binary([io_lib:format("~2.16.0b", [Byte]) || <<Byte>> <= Bytes]).

safe_delete(Path) ->
    case file:delete(Path) of
        ok -> ok;
        {error, enoent} -> ok;
        {error, Reason} -> {error, Reason}
    end.

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

seal_and_recover_round_trip_test() ->
    Dir = test_dir("round-trip"),
    Partition = <<"tenant-a/orders/0">>,
    try
        {ok, F1} = bmscl_queue_frame:encode(7, 1, 1000, <<"r1">>, <<"one">>),
        {ok, F2} = bmscl_queue_frame:encode(7, 2, 1001, <<"r2">>, <<"two">>),
        {ok, M1} = seal(Dir, Partition, [F1, F2]),
        ?assertEqual(1, maps:get(base_sequence, M1)),
        ?assertEqual(2, maps:get(last_sequence, M1)),
        ?assertEqual(file_sync, maps:get(durability, M1)),
        {ok, Inventory} = recover(Dir, Partition),
        ?assertEqual(1, maps:get(earliest_sequence, Inventory)),
        ?assertEqual(2, maps:get(last_sequence, Inventory)),
        ?assertEqual(1, length(maps:get(segments, Inventory))),
        ?assertEqual([], maps:get(quarantined, Inventory)),
        {ok, SegmentBytes, _} = read_segment(Dir, Partition, 1),
        {ok, [F1, F2], _} = bmscl_queue_segment:decode(SegmentBytes)
    after
        rm_rf(Dir)
    end.

idempotent_reseal_returns_same_segment_test() ->
    Dir = test_dir("idempotent"),
    Partition = <<"p-1">>,
    try
        {ok, F1} = bmscl_queue_frame:encode(1, 1, 1, <<>>, <<"one">>),
        {ok, M1} = seal(Dir, Partition, [F1]),
        {ok, M2} = seal(Dir, Partition, [F1]),
        ?assertEqual(maps:get(path, M1), maps:get(path, M2)),
        ?assertEqual(maps:get(digest, M1), maps:get(digest, M2))
    after
        rm_rf(Dir)
    end.

stale_temp_file_is_quarantined_test() ->
    Dir = test_dir("temp"),
    Partition = <<"p-1">>,
    try
        {ok, PartitionDir} = partition_dir(Dir, Partition),
        ok = filelib:ensure_dir(filename:join(PartitionDir, ".ensure")),
        Temp = filename:join(PartitionDir, ".tmp-crashed.bmsg"),
        ok = file:write_file(Temp, <<"partial">>),
        {ok, Inventory} = recover(Dir, Partition),
        [Item] = maps:get(quarantined, Inventory),
        ?assertEqual(stale_temp, maps:get(reason, Item)),
        ?assertEqual(false, filelib:is_file(Temp)),
        ?assert(filelib:is_file(maps:get(destination, Item)))
    after
        rm_rf(Dir)
    end.

corrupt_sealed_file_is_quarantined_test() ->
    Dir = test_dir("corrupt"),
    Partition = <<"p-1">>,
    try
        {ok, F1} = bmscl_queue_frame:encode(1, 1, 1, <<>>, <<"one">>),
        {ok, Manifest} = seal(Dir, Partition, [F1]),
        Path = maps:get(path, Manifest),
        {ok, Bytes} = file:read_file(Path),
        Size = byte_size(Bytes),
        <<Prefix:(Size - 33)/binary, Byte:8, Digest:32/binary>> = Bytes,
        Corrupt = <<Prefix/binary, (Byte bxor 1):8, Digest/binary>>,
        ok = file:write_file(Path, Corrupt),
        {ok, Inventory} = recover(Dir, Partition),
        ?assertEqual([], maps:get(segments, Inventory)),
        [Item] = maps:get(quarantined, Inventory),
        ?assertMatch({segment_decode_failed, _}, maps:get(reason, Item)),
        ?assert(filelib:is_file(maps:get(destination, Item)))
    after
        rm_rf(Dir)
    end.

recovery_rejects_segment_gap_test() ->
    Dir = test_dir("gap"),
    Partition = <<"p-1">>,
    try
        {ok, F1} = bmscl_queue_frame:encode(1, 1, 1, <<>>, <<"one">>),
        {ok, F3} = bmscl_queue_frame:encode(1, 3, 3, <<>>, <<"three">>),
        {ok, _} = seal(Dir, Partition, [F1]),
        {ok, _} = seal(Dir, Partition, [F3]),
        ?assertEqual({error, {segment_gap, 2, 3}}, recover(Dir, Partition))
    after
        rm_rf(Dir)
    end.

partition_id_is_not_used_as_path_test() ->
    Dir = test_dir("path"),
    {ok, PartitionDir} = partition_dir(Dir, <<"../../escape/me">>),
    ?assertEqual(false, lists:member($., filename:basename(PartitionDir))),
    ?assert(lists:prefix(filename:absname(Dir), filename:absname(PartitionDir))),
    rm_rf(Dir).

test_dir(Name) ->
    Unique = integer_to_list(erlang:unique_integer([positive, monotonic])),
    filename:join([tmp_root(), "bmscl-queue-store-" ++ Name ++ "-" ++ Unique]).

tmp_root() ->
    case os:getenv("TMPDIR") of
        false -> "/tmp";
        Value -> Value
    end.

rm_rf(Path) ->
    case filelib:is_dir(Path) of
        true ->
            case file:list_dir(Path) of
                {ok, Names} ->
                    lists:foreach(fun(Name) -> rm_rf(filename:join(Path, Name)) end, Names);
                {error, _} ->
                    ok
            end,
            _ = file:del_dir(Path),
            ok;
        false ->
            _ = file:delete(Path),
            ok
    end.

-endif.
