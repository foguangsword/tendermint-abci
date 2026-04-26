package org.example;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
public class keyUtil {
    // 生成 Ed25519 密钥对
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }

    // 公钥 → Base64 字符串（用于存储）
    public static String publicKeyToBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    // Base64 字符串 → 公钥对象（用于验签）
    public static PublicKey base64ToPublicKey(String base64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    // 签名
    public static String sign(String msg, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(msg.getBytes());
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    // 验签
    public static boolean verify(String msg, String signatureBase64, String publicKeyBase64) throws Exception {
        PublicKey publicKey = base64ToPublicKey(publicKeyBase64);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(msg.getBytes());
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
        return verifier.verify(signatureBytes);
    }
}
