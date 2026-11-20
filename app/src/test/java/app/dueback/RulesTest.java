package app.dueback;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class RulesTest {
    @Test public void threeRefundCredits() {
        long total=Rules.paise("1,200");
        long bank=Rules.paise("900"), wallet=Rules.paise("200");
        assertEquals(10000,Accounting.remaining(total,bank+wallet));
        assertEquals(0,Accounting.remaining(total,bank+wallet+10000));
    }
    @Test public void promiseIsSeparateFromCredit() {
        assertEquals(Rules.Kind.PROMISE,Rules.parse("Refund initiated for ₹800").kind);
        assertEquals(Rules.Kind.PROMISE,Rules.parse("Your refund of Rs. 800 will be credited soon").kind);
        assertEquals(Rules.Kind.CREDIT,Rules.parse("INR 800 credited to your bank").kind);
        assertEquals(Rules.Kind.CANCEL,Rules.parse("Your order was cancelled").kind);
        assertEquals(Rules.Kind.IGNORE,Rules.parse("Requested ₹800 for lunch").kind);
        assertEquals(Rules.Kind.IGNORE,Rules.parse("INR 800 debited from your account").kind);
    }
    @Test public void billSharesIncludeMe() {
        long[] split=Rules.equal(90000,3);
        assertEquals(60000,split[1]+split[2]);
        assertEquals(10000,Accounting.remaining(split[1],20000));
        assertArrayEquals(new long[]{34,33,33},Rules.equal(100,3));
    }
    @Test public void twoBillsNeedSelection() {
        assertTrue(Accounting.compatible(50000,50000,"mira@bank","mira@bank"));
        assertFalse(Accounting.autoMatch(true,2));
        assertFalse(Accounting.autoMatch(false,1));
        assertFalse(Accounting.compatible(50000,50000,Rules.identity("Mira"),Rules.identity("Mira")));
        assertTrue(Accounting.autoMatch(true,1));
    }
    @Test public void sourceIndependentReference() {
        assertTrue(Accounting.duplicate("abc123","csv/2","ABC123","notification/1"));
        assertFalse(Accounting.duplicate("abc124","csv/3","ABC123","notification/1"));
        assertFalse(Accounting.duplicate(null,null,null,null));
    }
    @Test public void splitNeedsConsentAndCannotOverdraw() {
        Accounting.checkAllocation(50000,50000,50000,50000,false);
        reject(()->Accounting.checkAllocation(50000,50000,50000,20000,false));
        reject(()->Accounting.checkAllocation(50000,30000,50000,30001,true));
        reject(()->Accounting.checkAllocation(50000,30000,10000,10001,true));
        Accounting.checkAllocation(50000,30000,30000,30000,true);
    }
    @Test public void parsePayerAndDestination() {
        Rules.Parsed p=Rules.parse("Received ₹200 from mira@bank into your bank account. UTR: 123456789001");
        assertEquals("mira@bank",p.payer);
        assertEquals("123456789001",p.reference);
        assertEquals("bank account",p.destination);
        assertEquals("200",p.amount);
        assertEquals(Rules.Kind.CREDIT,p.kind);
        assertEquals("+919876543210",Rules.identity("+91 98765 43210"));
    }
    @Test public void parseRefundDetails() {
        Rules.Parsed p=Rules.parse("Juniper: refund initiated for ₹1,200.50, order #ABC-22 expected by 2026-12-02 to your wallet");
        assertEquals("Juniper",p.merchant);assertEquals("ABC-22",p.order);
        assertEquals("2026-12-02",p.date);assertEquals("wallet",p.destination);
        assertEquals(120050,Rules.paise(p.amount));
    }
    @Test public void punctuationAndIndianDates() {
        Rules.Parsed p=Rules.parse("Refund initiated for ₹800, expected by 31/12/2026.");
        assertEquals(80000L,Rules.paise(p.amount));
        assertEquals("2026-12-31",p.date);
    }
    @Test public void csvPreservesQuotedFields() {
        assertEquals(Arrays.asList("1,200","a\"b"),Rules.csv("amount,payer\r\n\"1,200\",\"a\"\"b\"").get(1));
        reject(()->Rules.csv("\"unclosed"));
    }
    @Test public void distinguishBalanceFromTransferAndFlagUncertainAmounts() {
        assertEquals("450",Rules.parse("Available balance INR 5,000\nReceived INR 450 from mira@bank").amount);
        assertEquals(Rules.Kind.REVIEW,Rules.parse("Credited INR 100 or INR 200").kind);
        assertEquals(Rules.Kind.REVIEW,Rules.parse("Pending payment of INR 100 credited").kind);
        assertEquals(Rules.Kind.IGNORE,Rules.parse("Lunch at 2 PM").kind);
    }
    private static void reject(Runnable test) {
        try { test.run(); fail("Expected rejection"); } catch(IllegalArgumentException expected) { }
    }
}

