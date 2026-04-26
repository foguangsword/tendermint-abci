package org.example;

public class Account {
    public String id;
    public String name;
    public String publicKey;
    public long balance;
    public long nonce;

    public Account(String id, String name, String publicKey, long balance, long nonce) {
        this.id = id;
        this.name = name;
        this.publicKey = publicKey;
        this.balance = balance;
        this.nonce = nonce;
    }
}
