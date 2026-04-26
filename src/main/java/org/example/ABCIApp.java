package org.example;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import tendermint.abci.Types.*;

public class ABCIApp  extends tendermint.abci.ABCIApplicationGrpc.ABCIApplicationImplBase{

    private final StateManager state;

    public ABCIApp() {
        this.state = new StateManager("abci-state.db");
    }

    public StateManager getState(){
        return this.state;
    }

    //Tendermint 握手顺序是 Echo → Info → InitChain(创世才执行，即前一步info abci返回的区块高度是0)
    @Override
    public void info(RequestInfo request, StreamObserver<ResponseInfo> responseObserver) {
        System.out.println("Info called");

        responseObserver.onNext(ResponseInfo.newBuilder()
                .setData("tendermint-abci-app")
                .setVersion("0.0.1")
                .setAppVersion(0)
                .setLastBlockHeight(state.getLastBlockHeight())
                .setLastBlockAppHash(ByteString.copyFrom(state.getLastAppHash()))
                .build());
        responseObserver.onCompleted();
    }

    //echo握手
    @Override
    public void echo(RequestEcho request, StreamObserver<ResponseEcho> responseObserver) {
        responseObserver.onNext(ResponseEcho.newBuilder()
                .setMessage(request.getMessage())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void flush(RequestFlush request, StreamObserver<ResponseFlush> responseObserver) {
        responseObserver.onNext(ResponseFlush.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void initChain(RequestInitChain request, StreamObserver<ResponseInitChain> responseObserver) {
        state.resetState();
        //从genesis.json中读取初始账户
        if (!request.getAppStateBytes().isEmpty()) {
            String appStateJson = request.getAppStateBytes().toStringUtf8();
            System.out.println("AppState from genesis: " + appStateJson);
            JSONObject appState = JSON.parseObject(appStateJson);
            JSONArray accounts = appState.getJSONArray("accounts");
            if (accounts == null || accounts.isEmpty()) {
                System.out.println("Warning: no accounts found in genesis app_state");
            } else {
                for (int i = 0; i < accounts.size(); i++) {
                    JSONObject acc = accounts.getJSONObject(i);
                    state.initGenesisAccount(
                            acc.getString("id"),
                            acc.getString("name"),
                            acc.getString("publicKey"),
                            acc.getLong("balance")
                    );
                }
            }

        }

        responseObserver.onNext(ResponseInitChain.newBuilder().build());
        responseObserver.onCompleted();
    }

    // 交易进入 mempool 前的预检（验签 + 余额检查）
    @Override
    public void checkTx(RequestCheckTx request, StreamObserver<ResponseCheckTx> responseObserver) {
        String txJson = request.getTx().toStringUtf8();
        System.out.println("CheckTx: " + txJson);

        ReturnCode returnCode = validateTx(txJson);
        responseObserver.onNext(ResponseCheckTx.newBuilder()
                .setCode(returnCode.code)
                .setLog(returnCode.getMsg())
                .build());
        responseObserver.onCompleted();
    }

    //  区块提交时执行转账
    @Override
    public void deliverTx(RequestDeliverTx request, StreamObserver<ResponseDeliverTx> responseObserver) {
        String txJson = request.getTx().toStringUtf8();
        System.out.println("DeliverTx: " + txJson);
        ReturnCode returnCode;
        try{
            Transaction tx = Transaction.fromJson(txJson);
            returnCode = state.executeTransfer(tx);
        }catch (Exception e){
            System.out.println("Deliver tx failed: " + e.getMessage());
            returnCode = ReturnCode.SYSTEM_ERROR;
        }
        responseObserver.onNext(ResponseDeliverTx.newBuilder()
                .setCode(returnCode.code)
                .setLog(returnCode.getMsg())
                .build());
        responseObserver.onCompleted();
    }

    // 查询账户
    @Override
    public void query(RequestQuery request, StreamObserver<ResponseQuery> responseObserver) {
        String accountId = request.getData().toStringUtf8();
        Account account = state.getAccount(accountId);
        ResponseQuery.Builder builder = ResponseQuery.newBuilder();
        if (account == null) {
            builder.setCode(ReturnCode.ACCOUNT_NOT_FOUND.code).setLog(ReturnCode.ACCOUNT_NOT_FOUND.getMsg());
        } else {
            builder.setCode(ReturnCode.SUCCESS.code)
                    .setLog("balance: " + account.balance)
                    .setValue(ByteString.copyFromUtf8(JSON.toJSONString(account)));
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    //tendermint调用顺序 ： beginBlock -> deliverTx ...deliverTx -> endBlock -> commit
    @Override
    public void commit(RequestCommit request, StreamObserver<ResponseCommit> responseObserver) {
        byte[] appHash = state.commit();
        responseObserver.onNext(ResponseCommit.newBuilder()
                .setData(ByteString.copyFrom(appHash))
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void beginBlock(RequestBeginBlock request, StreamObserver<ResponseBeginBlock> responseObserver) {
        state.beginBlock(request.getHeader().getHeight());
        responseObserver.onNext(ResponseBeginBlock.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void endBlock(RequestEndBlock request, StreamObserver<ResponseEndBlock> responseObserver) {
        responseObserver.onNext(ResponseEndBlock.newBuilder().build());
        responseObserver.onCompleted();
    }

    private ReturnCode validateTx(String txJson) {
        try {
            Transaction tx = Transaction.fromJson(txJson);
            Account from = state.getAccount(tx.fromId);
            if (from == null) return ReturnCode.ADDRESS_NULL; // 账户不存在
            if (from.balance < tx.amount) return ReturnCode.LOW_BALANCE;  // 余额不足
            if (tx.nonce != from.nonce + 1) return ReturnCode.NONCE_INVALID; //nonce错误

            if (tx.signature == null || tx.signature.isEmpty()) { //签名为空
                System.out.println("CheckTx failed: missing signature");
                return ReturnCode.SIGN_ERROR;
            }
            boolean valid = keyUtil.verify(tx.getSignContent(), tx.signature, from.publicKey);
            if (!valid) {
                System.out.println("CheckTx failed: invalid signature");
                return ReturnCode.SIGN_ERROR;  //验签失败
            }

            return ReturnCode.SUCCESS;
        } catch (Exception e) {
            return ReturnCode.SYSTEM_ERROR; // 解析失败
        }
    }
}
