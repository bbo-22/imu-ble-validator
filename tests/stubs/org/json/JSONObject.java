package org.json;
/** Test-only JSON encoder; Android's own JSONObject is packaged by the OS. */
public class JSONObject {
 public static final Object NULL=new Object();
 private final java.util.Map<String,Object> values=new java.util.LinkedHashMap<>();
 public void put(String k,Object v){values.put(k,v);}
 private String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
 public String toString(){StringBuilder b=new StringBuilder("{");for(java.util.Map.Entry<String,Object> e:values.entrySet()){if(b.length()>1)b.append(',');b.append(quote(e.getKey())).append(':');Object v=e.getValue();b.append(v==NULL?"null":v instanceof Number||v instanceof Boolean?v:quote(String.valueOf(v)));}return b.append('}').toString();}
}
