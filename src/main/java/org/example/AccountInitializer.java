package org.example;


import java.security.KeyPair;

public class AccountInitializer {
    public static void main(String[] args) throws Exception {
        String[] names = {"alice", "bob", "carol"};
        for (String name : names) {
            KeyPair keyPair = keyUtil.generateKeyPair();
            System.out.println("=== " + name + " ===");
            System.out.println("PublicKey:  " + keyUtil.publicKeyToBase64(keyPair.getPublic()));
            // 私钥要妥善保存，客户端发交易时需要用
            System.out.println("PrivateKey: " +
                    java.util.Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        }
    }
}
