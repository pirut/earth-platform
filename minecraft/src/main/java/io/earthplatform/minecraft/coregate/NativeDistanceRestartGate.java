package io.earthplatform.minecraft.coregate;

/** Constant-space old-epoch barrier; an ABA selection never revives a prior drain receipt. */
final class NativeDistanceRestartGate {
    private NativeDistanceChangePayload latest;
    private boolean clientDrained,serverDrained,failed;
    /** An immediate predecessor may coalesce only while the new actor has not committed any pair. */
    static boolean openingEpoch(long actorEpoch,long predecessorEpoch,long predecessorSerial,boolean uncommittedContinuation,NativeDistanceChangePayload request){
        return request.epoch()==actorEpoch||uncommittedContinuation&&request.kind()==NativeDistanceChangePayload.REQUEST
                &&predecessorEpoch>0&&request.epoch()==predecessorEpoch&&request.serial()>predecessorSerial;
    }
    NativeDistanceRestartGate(NativeDistanceChangePayload first){if(!request(first))throw new IllegalArgumentException("Initial distance request required");}
    boolean request(NativeDistanceChangePayload request){
        if(failed||request.kind()!=NativeDistanceChangePayload.REQUEST||latest!=null&&(request.epoch()!=latest.epoch()||request.serial()<=latest.serial()))return false;
        latest=request;clientDrained=false;return true;
    }
    boolean clientDrained(NativeDistanceChangePayload receipt){if(failed||receipt.kind()!=NativeDistanceChangePayload.CLIENT_DRAINED||!latest.sameSelection(receipt))return false;clientDrained=true;return true;}
    void serverDrained(boolean successful){if(successful)serverDrained=true;else failed=true;}
    void fail(){failed=true;}
    boolean ready(){return !failed&&clientDrained&&serverDrained&&latest.effective()==latest.selected();}
    boolean failed(){return failed;}
    NativeDistanceChangePayload latest(){return latest;}
}
