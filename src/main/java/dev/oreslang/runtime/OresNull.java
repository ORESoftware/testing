package dev.oreslang.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** Guest-language null/void value safe to expose through the Polyglot API. */
@ExportLibrary(InteropLibrary.class)
public final class OresNull implements TruffleObject {
    public static final OresNull INSTANCE = new OresNull();

    private OresNull() { }

    @ExportMessage
    boolean isNull() {
        return true;
    }

    @Override
    public String toString() {
        return "null";
    }
}
