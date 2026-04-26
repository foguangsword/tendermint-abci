package org.example;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class SignTest {
    public static void main(String[] args) throws Exception {
        String msg = "This is a very important message.";
        //生成密钥对
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();
        String pk = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        String sk = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        System.out.println("Public Key:" + pk);
        System.out.println("Private Key:" + sk);

        //签名
        String signatureBase64 = sign(msg, sk);
        System.out.println("Signature:" + signatureBase64);

        //验签
        boolean success = verify(msg, signatureBase64, pk);
        System.out.println("验签结果：" + success);

        success = verify(msg + "这是加上去的篡改内容", signatureBase64, pk);
        System.out.println("验签结果：" + success);

    }

    public static String sign(String msg, String skBase64) throws Exception{
        //从base64中把PrivateKey反序列化还原回来，KeySpec用PKCS8EncodedKeySpec
        byte[] keyBytes =  Base64.getDecoder().decode(skBase64);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey); //设置PrivateKey
        signer.update(msg.getBytes()); //设置要签名的数据
        byte[] signedBytes = signer.sign(); //生成签名
        String encodedMsg = Base64.getEncoder().encodeToString(signedBytes); //序列化编码，base64格式
        return encodedMsg;
    }

    public static boolean verify(String msg, String signatureBase64, String pkBase64) throws Exception{
        //从base64还原PublicKey, KeySpec用 X509EncodedKeySpec
        byte[] keyBytes = Base64.getDecoder().decode(pkBase64);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
        Signature verifier = Signature.getInstance("Ed25519");

        verifier.initVerify(publicKey);
        verifier.update(msg.getBytes());
        byte[] signedBytes = Base64.getDecoder().decode(signatureBase64);
        boolean verify = verifier.verify(signedBytes);
        return verify;
    }
}
