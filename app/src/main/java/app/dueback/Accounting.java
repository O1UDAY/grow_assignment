package app.dueback;

import java.util.Objects;

/** Pure rules shared by every transaction source and by the Room write path. */
public final class Accounting {
    private Accounting() {}

    public static long remaining(long total,long allocated) {
        if (allocated < 0 || allocated > total) throw new IllegalStateException("Inconsistent ledger balance.");
        return total - allocated;
    }

    public static void checkAllocation(long original,long available,long balance,long amount,boolean split) {
        if(amount<=0 || amount>available || amount>balance)
            throw new IllegalArgumentException("Amount exceeds the available balance.");
        if(!split && (original!=available || amount!=original))
            throw new IllegalArgumentException("Choose split to allocate part of a payment.");
    }

    public static boolean compatible(long amount,long balance,String evidence,String expected) {
        return amount>0 && amount<=balance && !evidence.isEmpty() && evidence.equalsIgnoreCase(expected);
    }

    public static boolean duplicate(String reference,String event,String recordedRef,String recordedEvent) {
        return (reference!=null && reference.equalsIgnoreCase(recordedRef))
            || (event!=null && Objects.equals(event,recordedEvent));
    }

    public static boolean autoMatch(boolean confirmed,int compatibleCount) {
        return confirmed && compatibleCount==1;
    }
}

