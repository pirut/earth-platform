package io.earthplatform.minecraft.coregate;

/** One old-epoch transition; serials distinguish every observed mutation including ABA. */
final class NativeDistanceClientTransition {
    record Selection(int selected,int effective,int serverLimit) {
        Selection { if(selected<2||selected>32||effective<0||effective>32||serverLimit<0||serverLimit>32)throw new IllegalArgumentException("Unsupported actual native distance"); }
    }
    private final long epoch;
    private long serial;
    private NativeDistanceChangePayload latest;
    private boolean drainRequested,acknowledged,failed;
    NativeDistanceClientTransition(long epoch){if(epoch<=0)throw new IllegalArgumentException();this.epoch=epoch;}
    NativeDistanceChangePayload mutation(Selection actual){
        if(failed)throw new IllegalStateException("Native distance restart failed");
        latest=NativeDistanceChangePayload.request(epoch,Math.incrementExact(serial),actual.selected,actual.effective,actual.serverLimit);
        serial=latest.serial();drainRequested=false;acknowledged=false;return latest;
    }
    NativeDistanceChangePayload observe(Selection actual){
        if(latest==null||latest.selected()!=actual.selected||latest.effective()!=actual.effective||latest.serverLimit()!=actual.serverLimit)return mutation(actual);
        return null;
    }
    boolean drain(NativeDistanceChangePayload message){if(failed||message.kind()!=NativeDistanceChangePayload.DRAIN||latest==null||!latest.sameSelection(message))return false;drainRequested=true;return true;}
    NativeDistanceChangePayload physicallyDrained(){
        if(failed||!drainRequested||acknowledged)return null;
        acknowledged=true;return NativeDistanceChangePayload.drained(epoch,latest.serial(),latest.selected(),latest.effective(),latest.serverLimit());
    }
    boolean permitsPreflight(){return !failed&&acknowledged;}
    boolean waitingForDrain(){return !failed&&drainRequested&&!acknowledged;}
    long epoch(){return epoch;}
    NativeDistanceChangePayload latest(){return latest;}
    boolean failed(){return failed;}
    void fail(){failed=true;}
}
