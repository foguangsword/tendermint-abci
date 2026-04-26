package org.example;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;

public class Transaction {
    public String fromId;
    public String toId;
    public long amount;

    public long nonce;
    public String signature;

    public static Transaction fromJson(String json) {
        return JSON.parseObject(json, Transaction.class);
    }

    public String toJson(){
        return JSON.toJSONString(this);
    }

    @JSONField(serialize = false)
    public String getSignContent(){
        return fromId + ":" + toId + ":" + amount + ":" + nonce;
    }
}
