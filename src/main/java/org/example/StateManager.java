package org.example;
import org.mapdb.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
public class StateManager {
    private final DB db;
    private final HTreeMap<String, Account> accounts;
    private final Atomic.Var<Long> lastBlockHeight;
    private final Atomic.Var<byte[]> lastAppHash;

    // 当前正在处理的区块高度（jvm内存中，Commit 时刷盘）
    private volatile long pendingHeight = 0;

    public StateManager(String dbPath) {
        this.db = DBMaker.fileDB(dbPath)
                .fileMmapEnable()
                .transactionEnable()
                .make();

        this.accounts = db.hashMap("accounts")
                .keySerializer(Serializer.STRING)
                .valueSerializer(new AccountSerializer())
                .createOrOpen();

        this.lastBlockHeight = db.atomicVar("lastBlockHeight", Serializer.LONG).createOrOpen();
        this.lastAppHash = db.atomicVar("lastAppHash", Serializer.BYTE_ARRAY).createOrOpen();
    }

    // ===== 供 ABCIApp 调用 =====
    public long getLastBlockHeight() {
        Long height = lastBlockHeight.get();
        //创世时，db里还没有lastBlockHeight，但tendermint node也会在info时要求abci返回，所以返回0
        return height==null ? 0L : height;
    }

    public byte[] getLastAppHash() {
        byte[] appHash = lastAppHash.get();
        if(appHash == null)
            appHash = "".getBytes();
        return appHash;
    }

    public void initGenesisAccount(String id, String name, String publicKey, long balance) {
        accounts.put(id, new Account(id, name, publicKey, balance, 0L));
    }


    public void resetState(){
        accounts.clear();
        lastBlockHeight.set(0L);
        lastAppHash.set(new byte[0]);
        db.commit();
    }

    public void beginBlock(long height) {
        this.pendingHeight = height;
    }

    public synchronized ReturnCode executeTransfer(Transaction tx) {
        Account from = accounts.get(tx.fromId);
        Account to = accounts.get(tx.toId);
        if (from == null || to == null) return ReturnCode.ADDRESS_NULL;
        if (from.balance < tx.amount) return ReturnCode.LOW_BALANCE;

        from.balance -= tx.amount;
        from.nonce = tx.nonce;
        to.balance += tx.amount;
        accounts.put(tx.fromId, from);  // MapDB 需要重新 put 才会感知变更
        accounts.put(tx.toId, to);
        return ReturnCode.SUCCESS;
    }

    public long queryBalance(String accountId) {
        Account a = accounts.get(accountId);
        return a == null ? -1 : a.balance;
    }

    public Account getAccount(String accountId){
        return accounts.get(accountId);
    }

    public byte[] commit() {
        byte[] appHash = computeAppHash();
        lastBlockHeight.set(pendingHeight);
        lastAppHash.set(appHash);
        db.commit();  // 原子刷盘 commit之前是在mmap里，os级缓存
        return appHash;
    }

    // ===== 内部方法 =====
    private byte[] computeAppHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ArrayList<String> ids = new ArrayList<>(accounts.keySet());
            Collections.sort(ids);
            for (String id : ids) {
                Account a = accounts.get(id);
                md.update(a.id.getBytes(StandardCharsets.UTF_8));
                md.update(a.name.getBytes(StandardCharsets.UTF_8));
                md.update(a.publicKey.getBytes(StandardCharsets.UTF_8));
                md.update(ByteBuffer.allocate(8).putLong(a.balance).array());
                md.update(ByteBuffer.allocate(8).putLong(a.nonce).array());
            }
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void close() {
        db.close();
    }

    // ===== MapDB 序列化器 =====
    private static class AccountSerializer implements Serializer<Account> {
        @Override
        public void serialize(DataOutput2 out, Account value) throws IOException {
            out.writeUTF(value.id);
            out.writeUTF(value.name);
            out.writeUTF(value.publicKey);
            out.writeLong(value.balance);
            out.writeLong(value.nonce);
        }

        @Override
        public Account deserialize(DataInput2 in, int available) throws IOException {
            return new Account(in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(), in.readLong());
        }

    }
}
