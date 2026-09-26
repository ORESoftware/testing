-module(bmscl_queue_segment).

-export([build/1, decode/1, manifest/1]).

-define(VERSION, 1).
-define(FLAGS, 0).
-define(HEADER_SIZE, 26).
-define(DIGEST_SIZE, 32).
-define(MAX_RECORDS, 65536).
-define(MAX_SEGMENT_BODY_BYTES, 67108864).

-spec build([binary()]) -> {ok, binary(), map()} | {error, term()}.
build(Frames) when is_list(Frames), Frames =/= [], length(Frames) =< ?MAX_RECORDS ->
    case validate_frames(Frames) of
        {ok, Decoded} ->
            [First | _] = Decoded,
            BaseSequence = maps:get(sequence, First),
            Count = length(Frames),
            Body = iolist_to_binary([
                <<(byte_size(Frame)):32/unsigned-big, Frame/binary>>
                || Frame <- Frames
            ]),
            BodyBytes = byte_size(Body),
            case BodyBytes =< ?MAX_SEGMENT_BODY_BYTES of
                false ->
                    {error, segment_too_large};
                true ->
                    Header = <<
                        "BMSG",
                        ?VERSION:8/unsigned,
                        ?FLAGS:8/unsigned,
                        BaseSequence:64/unsigned-big,
                        Count:32/unsigned-big,
                        BodyBytes:64/unsigned-big
                    >>,
                    Covered = <<Header/binary, Body/binary>>,
                    Digest = crypto:hash(sha256, Covered),
                    Segment = <<Covered/binary, Digest/binary>>,
                    {ok, Segment, make_manifest(Segment, Digest, Decoded)}
            end;
        {error, Reason} ->
            {error, Reason}
    end;
build([]) ->
    {error, empty_segment};
build(_) ->
    {error, invalid_segment_records}.

-spec decode(binary()) -> {ok, [binary()], map()} | {error, term()}.
decode(Segment) when is_binary(Segment),
                     byte_size(Segment) >= ?HEADER_SIZE + ?DIGEST_SIZE ->
    CoveredSize = byte_size(Segment) - ?DIGEST_SIZE,
    <<Covered:CoveredSize/binary, ExpectedDigest:?DIGEST_SIZE/binary>> = Segment,
    case crypto:hash(sha256, Covered) =:= ExpectedDigest of
        false ->
            {error, segment_digest_mismatch};
        true ->
            decode_verified(Segment, Covered, ExpectedDigest)
    end;
decode(_) ->
    {error, invalid_segment}.

-spec manifest(binary()) -> {ok, map()} | {error, term()}.
manifest(Segment) ->
    case decode(Segment) of
        {ok, _Frames, Manifest} ->
            {ok, Manifest};
        {error, Reason} ->
            {error, Reason}
    end.

decode_verified(Segment, <<
    "BMSG",
    ?VERSION:8/unsigned,
    ?FLAGS:8/unsigned,
    BaseSequence:64/unsigned-big,
    Count:32/unsigned-big,
    BodyBytes:64/unsigned-big,
    Body/binary
>>, Digest)
  when BaseSequence > 0,
       Count > 0, Count =< ?MAX_RECORDS,
       BodyBytes =< ?MAX_SEGMENT_BODY_BYTES ->
    case byte_size(Body) =:= BodyBytes of
        false ->
            {error, invalid_segment_body_length};
        true ->
            case parse_frames(Body, Count, []) of
                {ok, Frames} ->
                    case validate_frames(Frames) of
                        {ok, Decoded} ->
                            [First | _] = Decoded,
                            case maps:get(sequence, First) =:= BaseSequence of
                                true ->
                                    {ok, Frames, make_manifest(Segment, Digest, Decoded)};
                                false ->
                                    {error, segment_base_sequence_mismatch}
                            end;
                        {error, Reason} ->
                            {error, Reason}
                    end;
                {error, Reason} ->
                    {error, Reason}
            end
    end;
decode_verified(_Segment, <<"BMSG", Version:8/unsigned, _/binary>>, _Digest)
  when Version =/= ?VERSION ->
    {error, {unsupported_segment_version, Version}};
decode_verified(_, _, _) ->
    {error, invalid_segment_header}.

parse_frames(<<>>, 0, Acc) ->
    {ok, lists:reverse(Acc)};
parse_frames(_Body, 0, _Acc) ->
    {error, trailing_segment_bytes};
parse_frames(<<Length:32/unsigned-big, Rest/binary>>, Remaining, Acc)
  when Length > 0, byte_size(Rest) >= Length ->
    <<Frame:Length/binary, Tail/binary>> = Rest,
    parse_frames(Tail, Remaining - 1, [Frame | Acc]);
parse_frames(_, _, _) ->
    {error, invalid_segment_frame_boundary}.

validate_frames(Frames) ->
    validate_frames(Frames, undefined, []).

validate_frames([], _PreviousSequence, Acc) ->
    {ok, lists:reverse(Acc)};
validate_frames([Frame | Rest], PreviousSequence, Acc) when is_binary(Frame) ->
    case bmscl_queue_frame:decode(Frame) of
        {ok, Decoded} ->
            Sequence = maps:get(sequence, Decoded),
            case contiguous(PreviousSequence, Sequence) of
                true ->
                    validate_frames(Rest, Sequence, [Decoded | Acc]);
                false ->
                    {error, {non_contiguous_segment_sequence, PreviousSequence, Sequence}}
            end;
        {error, Reason} ->
            {error, {invalid_segment_record, Reason}}
    end;
validate_frames(_, _, _) ->
    {error, invalid_segment_record}.

contiguous(undefined, _Sequence) ->
    true;
contiguous(Previous, Sequence) ->
    Sequence =:= Previous + 1.

make_manifest(Segment, Digest, Decoded) ->
    First = hd(Decoded),
    Last = lists:last(Decoded),
    Terms = [maps:get(term, Record) || Record <- Decoded],
    #{
        schema => <<"bmscl.queue-segment/v1">>,
        base_sequence => maps:get(sequence, First),
        last_sequence => maps:get(sequence, Last),
        record_count => length(Decoded),
        byte_size => byte_size(Segment),
        digest => Digest,
        min_term => lists:min(Terms),
        max_term => lists:max(Terms)
    }.

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

segment_round_trip_test() ->
    {ok, F1} = bmscl_queue_frame:encode(7, 11, 1000, <<"r11">>, <<"one">>),
    {ok, F2} = bmscl_queue_frame:encode(7, 12, 1001, <<"r12">>, <<"two">>),
    {ok, F3} = bmscl_queue_frame:encode(8, 13, 1002, <<"r13">>, <<"three">>),
    {ok, Segment, Manifest0} = build([F1, F2, F3]),
    ?assertEqual(11, maps:get(base_sequence, Manifest0)),
    ?assertEqual(13, maps:get(last_sequence, Manifest0)),
    ?assertEqual(3, maps:get(record_count, Manifest0)),
    ?assertEqual(7, maps:get(min_term, Manifest0)),
    ?assertEqual(8, maps:get(max_term, Manifest0)),
    {ok, [F1, F2, F3], Manifest1} = decode(Segment),
    ?assertEqual(Manifest0, Manifest1).

non_contiguous_segment_is_rejected_test() ->
    {ok, F1} = bmscl_queue_frame:encode(7, 11, 1000, <<>>, <<"one">>),
    {ok, F3} = bmscl_queue_frame:encode(7, 13, 1002, <<>>, <<"three">>),
    ?assertEqual(
       {error, {non_contiguous_segment_sequence, 11, 13}},
       build([F1, F3])).

segment_corruption_is_rejected_test() ->
    {ok, F1} = bmscl_queue_frame:encode(7, 11, 1000, <<>>, <<"one">>),
    {ok, Segment, _} = build([F1]),
    Size = byte_size(Segment),
    <<Prefix:(Size - 33)/binary, Byte:8, Digest:32/binary>> = Segment,
    Corrupt = <<Prefix/binary, (Byte bxor 1):8, Digest/binary>>,
    ?assertEqual({error, segment_digest_mismatch}, decode(Corrupt)).

-endif.
