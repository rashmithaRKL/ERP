package lk.dexlanka.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.util.*;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
class Store {
    final JdbcTemplate db; final ObjectMapper json;
    Store(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    static String id() { return UUID.randomUUID().toString(); }
    static BigDecimal dec(Object v) { return v==null ? BigDecimal.ZERO : new BigDecimal(v.toString()); }
    static BigDecimal money(BigDecimal v) { return v.setScale(6,RoundingMode.HALF_UP); }
    static String str(Map<String,?> m,String k) { return Objects.toString(m.get(k),""); }
    static void require(boolean condition,String message) { if(!condition) throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
    List<Map<String,Object>> list(String sql,Object... args) { return db.queryForList(sql,args); }
    Map<String,Object> one(String sql,Object... args) { var rows=list(sql,args); if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found in your organization"); return rows.getFirst(); }
    String encode(Object data) { try { return json.writeValueAsString(data); } catch(Exception e) { throw new IllegalStateException(e); } }
    Map<String,Object> decode(String data) { try { return json.readValue(data,json.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class)); } catch(Exception e) { throw new IllegalStateException(e); } }
    void lock(Actor a) { one("SELECT id FROM tenants WHERE id=? FOR UPDATE",a.tenant()); }
    Map<String,Object> once(Actor a,String key,String action,Object body,Supplier<Map<String,Object>> work) {
        require(key!=null && key.matches("[a-zA-Z0-9_-]{8,120}"),"A valid Idempotency-Key is required");
        lock(a);
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(body).getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); }
        var previous=list("SELECT * FROM idempotency_keys WHERE tenant_id=? AND request_key=?",a.tenant(),key);
        if(!previous.isEmpty()) { var p=previous.getFirst(); require(action.equals(p.get("action")) && hash.equals(p.get("payload_hash")),"Idempotency key was already used for a different request"); return decode(p.get("response").toString()); }
        var result=work.get();
        db.update("INSERT INTO idempotency_keys(tenant_id,request_key,action,payload_hash,response) VALUES(?,?,?,?,?::jsonb)",a.tenant(),key,action,hash,encode(result));
        return result;
    }
    String number(Actor a,String prefix) { return prefix+"-"+java.time.Year.now().getValue()+"-"+UUID.randomUUID().toString().substring(0,6).toUpperCase(Locale.ROOT); }
    void audit(Actor a,String action,String reference,String detail) {
        db.update("INSERT INTO audit_events(id,tenant_id,actor,action,reference,detail) VALUES(?,?,?,?,?,?)",id(),a.tenant(),a.name(),action,reference,detail);
        db.update("INSERT INTO outbox_events(id,tenant_id,event_type,reference,payload) VALUES(?,?,?,?,?::jsonb)",id(),a.tenant(),action,reference,encode(Map.of("actor",a.name(),"detail",detail,"version",1)));
    }
    void openPeriod(Actor a) { require(list("SELECT 1 FROM financial_periods WHERE tenant_id=? AND closed=true AND end_date>=CURRENT_DATE",a.tenant()).isEmpty(),"The financial period is closed"); }
    void journal(Actor a,String reference,String description,String debit,String credit,BigDecimal amount) {
        openPeriod(a); if(amount.signum()==0) return; require(amount.signum()>0,"Journal amount must be positive");
        String entry=id();
        db.update("INSERT INTO journal_entries(id,tenant_id,reference,description,actor) VALUES(?,?,?,?,?)",entry,a.tenant(),reference,description,a.name());
        db.update("INSERT INTO journal_lines(id,tenant_id,entry_id,account,debit,credit) VALUES(?,?,?,?,?,0),(?,?,?,?,0,?)",id(),a.tenant(),entry,debit,money(amount),id(),a.tenant(),entry,credit,money(amount));
    }
    void validDate(String date) {try{java.time.LocalDate.parse(date);}catch(java.time.DateTimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a valid required-by calendar date");}}
    void quantity(String uom,BigDecimal qty) { require(!uom.equals("pcs") || qty.stripTrailingZeros().scale()<=0,"Pieces must be whole numbers; no implicit unit conversion is allowed"); }
    BigDecimal ledger(Actor a,String lot,BigDecimal delta,String reference,String reason) {return ledger(a,lot,delta,reference,reason,null);}
    BigDecimal ledger(Actor a,String lot,BigDecimal delta,String reference,String reason,BigDecimal explicitValue) {
        var l=one("SELECT l.*,i.uom FROM lots l JOIN items i ON i.tenant_id=l.tenant_id AND i.id=l.item_id WHERE l.tenant_id=? AND l.id=?",a.tenant(),lot);
        quantity(str(l,"uom"),delta);BigDecimal value=explicitValue;
        if(value==null && delta.signum()<0) {var b=one("SELECT * FROM stock_balances WHERE tenant_id=? AND lot_id=? FOR UPDATE",a.tenant(),lot);require(dec(b.get("quantity")).compareTo(delta.abs())>=0,"Insufficient stock");value=delta.abs().compareTo(dec(b.get("quantity")))==0?dec(b.get("value_amount")).negate():money(dec(b.get("value_amount")).multiply(delta).divide(dec(b.get("quantity")),12,RoundingMode.HALF_UP));}
        if(value==null) value=money(delta.multiply(dec(l.get("unit_cost"))));
        db.update("INSERT INTO stock_ledger(id,tenant_id,lot_id,item_id,warehouse_id,uom,delta,value_delta,unit_cost,reference,reason,actor) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id(),a.tenant(),lot,l.get("item_id"),l.get("warehouse_id"),l.get("uom"),money(delta),value,l.get("unit_cost"),reference,reason,a.name());
        return value;
    }
}
