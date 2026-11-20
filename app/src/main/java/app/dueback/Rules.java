package app.dueback;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

public final class Rules {
    private Rules() {}
    public enum Kind { CREDIT, PROMISE, CANCEL, REVIEW, IGNORE }
    public static long paise(String raw) {
        String value = raw.trim().replaceFirst("(?i)^(₹|INR|Rs\\.?)\\s*", "");
        if (!value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{2})*,[0-9]{3}|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Enter rupees with up to two decimal places.");
        return new BigDecimal(value.replace(",","")).movePointRight(2).longValueExact();
    }
    public static long[] equal(long amount,int count) {
        if (amount <= 0 || count < 1) throw new IllegalArgumentException("Check bill amount and members.");
        long[] shares = new long[count];
        for (int i=0;i<count;i++) shares[i]=amount/count+(i<amount%count?1:0);
        return shares;
    }
    public static String identity(String input) {
        String id=input.trim().toLowerCase(Locale.ROOT);
        if(id.matches("[\\w.\\-]{2,}@[a-z][a-z0-9._-]*")) return id;
        String phone=id.replaceAll("[ ()-]","");
        if(phone.matches("(?:\\+91|91)?[6-9][0-9]{9}")) return "+91"+phone.substring(phone.length()-10);
        return "";
    }
    static String field(String text,String re) {
        Matcher m=Pattern.compile(re,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text);
        return m.find()?m.group(1).trim():"";
    }
    public static class Parsed {
        public Kind kind;
        public boolean dateNeedsReview, amountNeedsReview;
        public String amount="", merchant="", reference="", order="", date="", destination="", payer="";
    }
    public static Parsed parse(String raw) {
        return parse(raw, null, java.time.ZoneId.systemDefault());
    }
    public static Parsed parse(String raw, java.time.Instant original, java.time.ZoneId zone) {
        String t=raw.replace('\u00a0',' ').trim();
        String l=t.toLowerCase(Locale.ROOT);
        Parsed p=new Parsed();
        // A balance line is context, not the amount transferred.
        String transactionLine = t.replaceAll("(?im)(?:avail(?:able)?|closing|current)\\s+(?:bal(?:ance)?)\\b[^\\n]*", "");
        String amountPattern = "(?:₹|\\bINR|\\bRs\\.?)\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]{1,2})?)(?![.0-9]\\d)";
        Matcher amounts = Pattern.compile(amountPattern, Pattern.CASE_INSENSITIVE).matcher(transactionLine);
        p.amount = amounts.find() ? amounts.group(1) : "";
        boolean ambiguousAmount = amounts.find();
        p.amountNeedsReview = ambiguousAmount;
        boolean refund=l.contains("refund");
        boolean future=l.matches("(?s).*(?:initiated|promised|processing|will be|to be credited|expected|being processed|on its way|has been issued).*");
        boolean incoming=l.matches("(?s).*\\b(?:credited|received|deposited)\\b.*");
        boolean unsafe=l.matches("(?s).*\\b(?:debit(?:ed)?|sent|paid to|request(?:s|ed)?|collect request|otp|failed|declined|not received|not credited|could receive|win|cashback offer)\\b.*");
        if(unsafe) p.kind=Kind.IGNORE;
        else if(refund && future) p.kind=Kind.PROMISE;
        else if(l.matches("(?s).*\\bcancel(?:led|ed|lation)\\b.*") && !incoming) p.kind=refund?Kind.PROMISE:Kind.CANCEL;
        else if(incoming && !future && !p.amount.isEmpty() &&
            (l.contains("credited") || l.contains("deposited") || l.matches("(?s).*(?:received\\s+(?:(?:inr|rs\\.?|₹)\\s*\\d|a payment|money)|(?:inr|rs\\.?|₹)\\s*[\\d,.]+\\s+(?:was\\s+)?received).*"))) p.kind=Kind.CREDIT;
        else if((refund || l.matches("(?s).*\\b(?:payment|transaction|transfer|credit)\\b.*")) && !p.amount.isEmpty()) p.kind=Kind.REVIEW;
        else if(l.matches("(?s).*\\b(?:payment|transfer|refund)\\s+(?:received|credited|processed|initiated)\\b.*")) p.kind=Kind.REVIEW;
        else p.kind=Kind.IGNORE;
        if (p.kind == Kind.CREDIT && (ambiguousAmount || l.matches("(?s).*\\b(?:pending|scheduled|not yet|reversed)\\b.*"))) p.kind = Kind.REVIEW;
        p.reference=field(t,"\\b(?:utr|rrn|upi\\s*ref(?:erence)?|txn\\s*(?:id|ref)|transaction\\s*(?:id|ref))\\s*(?:no\\.?\\s*)?[:#= -]*([a-z0-9][a-z0-9-]{4,})\\b");
        p.order=field(t,"\\border\\s*(?:id|ref(?:erence)?|no\\.?)?\\s*[:#= -]*([a-z0-9-]*\\d[a-z0-9-]*)\\b");
        p.merchant=field(t,"(?:merchant\\s*[:=]|(?:refund|order)\\s+from)\\s*([\\p{L}][\\p{L}0-9 &.-]{0,45}?)(?=\\s+(?:order|for|of|has|was|is|will)|[,;\\n]|$)");
        if(p.merchant.isEmpty()) p.merchant=field(t,"^([\\p{L}][\\p{L}0-9 &.-]{1,35}?)(?:\\s*[:|]|\\s+(?:order|refund)\\b)");
        p.date=field(t,"(?:by|expected(?:\\s+date)?|due(?:\\s+date)?|on)\\s*[:=]?\\s*(\\d{4}-\\d{2}-\\d{2})");
        if(p.date.isEmpty()){
            String days=field(t,"(?:within|in)\\s+(\\d{1,2})\\s+(?:calendar\\s+)?days?");
            if(!days.isEmpty()){
                if(original==null) p.dateNeedsReview=true;
                else p.date=original.atZone(zone).toLocalDate().plusDays(Integer.parseInt(days)).toString();
            }
            if(l.matches("(?s).*\\b(?:business|working)\\s+days?\\b.*")) {
                p.date=""; p.dateNeedsReview=true;
            }
        }
        if(p.date.isEmpty()){
            String numeric=field(t,"(?:by|expected|due|on)\\s*[:=]?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{4})");
            if(!numeric.isEmpty()){
                String[] parts=numeric.split("[/-]");
                try {
                    p.date=LocalDate.of(Integer.parseInt(parts[2]),Integer.parseInt(parts[1]),Integer.parseInt(parts[0])).toString();
                } catch(java.time.DateTimeException ignored) { /* Review screen accepts a corrected date. */ }
            }
        }
        p.destination=field(t,"(?:to|into|destination\\s*[:=])\\s+(?:your\\s+)?((?:[a-z]+\\s+)?(?:wallet|bank(?:\\s+account)?|card|a/c)(?:\\s+(?:ending\\s+(?:in\\s+)?)?[x*\\d]+)?)");
        p.payer=identity(field(t,"\\b(?:from|payer(?:\\s+id)?\\s*[:=]?)\\s+(?:[a-z ]{1,35}\\s*\\()?([\\w.-]+@[\\w.-]+|(?:\\+91)?[6-9]\\d{9})\\b"));
        if(p.payer.isEmpty()) p.payer=identity(field(t,"\\b(?:from|payer(?:\\s+id)?\\s*[:=]?)\\s+([\\w.-]+@[\\w.-]+|(?:\\+91)?[6-9]\\d{9})\\b"));
        return p;
    }

    public static List<List<String>> csv(String input) {
        List<List<String>> records=new ArrayList<>();
        List<String> row=new ArrayList<>();
        StringBuilder field=new StringBuilder();
        boolean quoted=false;
        for(int i=0;i<input.length();i++) {
            char c=input.charAt(i);
            if(c=='"') {
                if(quoted && i+1<input.length() && input.charAt(i+1)=='"') { field.append('"'); i++; }
                else quoted=!quoted;
            } else if(c==',' && !quoted) {row.add(field.toString().trim());field.setLength(0);}
            else if((c=='\n'||c=='\r') && !quoted) {
                if(c=='\r' && i+1<input.length() && input.charAt(i+1)=='\n') i++;
                row.add(field.toString().trim());
                if(!row.stream().allMatch(String::isEmpty)) records.add(row);
                row=new ArrayList<>();field.setLength(0);
            } else field.append(c);
        }
        if(quoted) throw new IllegalArgumentException("CSV contains an unclosed quote.");
        row.add(field.toString().trim());
        if(!row.stream().allMatch(String::isEmpty)) records.add(row);
        return records;
    }
}

