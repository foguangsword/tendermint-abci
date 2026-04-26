package org.example;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;
import org.bouncycastle.util.encoders.Hex;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
public class ClientDemo {
    public static void main(String[] args) throws Exception {
        Account account = queryAccount("alice");
        System.out.println(JSON.toJSONString(account));

        // alice 的私钥（从 AccountInitializer 输出里复制）
        String alicePrivateKeyBase64 = "MC4CAQAwBQYDK2VwBCIEIN20QL5UtLYUtankHQ1uaTk8mPmOzc47HykL7HbahZ7z";
        // 还原私钥对象
        byte[] keyBytes = Base64.getDecoder().decode(alicePrivateKeyBase64);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        // 构造交易
        Transaction tx = new Transaction();
        tx.fromId = "alice";
        tx.toId = "bob";
        tx.amount = 100;
        tx.nonce = account.nonce + 1;
        // 签名
        tx.signature = keyUtil.sign(tx.getSignContent(), privateKey);

        String txResult = transfer(tx);
        System.out.println(txResult);

        account = queryAccount("alice");
        System.out.println(JSON.toJSONString(account));
        account = queryAccount("bob");
        System.out.println(JSON.toJSONString(account));
    }

    private static String Get(String url) throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET().build();
        HttpResponse httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return httpResponse.body().toString();
    }

    private static Account queryAccount(String accountId) throws IOException, InterruptedException {
        String hex = Hex.toHexString(accountId.getBytes(StandardCharsets.UTF_8));
        String url = "http://localhost:26657/abci_query?data=0x" + hex;
        String response = Get(url);
        Object valueObj = JSONPath.eval(response, "$.result.response.value");
        if (valueObj == null) {
            return null;
        }
        String valueBase64 = (String) valueObj;
        byte[] decoded = Base64.getDecoder().decode(valueBase64);
        Account account = JSONObject.parseObject(new String(decoded), Account.class);
        return account;
    }

    private static String transfer(Transaction tx) throws IOException, InterruptedException {
        String hex = Hex.toHexString(tx.toJson().getBytes(StandardCharsets.UTF_8));
        String url = "http://localhost:26657/broadcast_tx_commit?tx=0x" + hex;
        String response = Get(url);
        return response;
    }
}
