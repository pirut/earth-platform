package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;

/** Channel-neutral retained ownership. No native callback, Host, server or event-loop invocation here.
 * The fixed cell index and maximum live-cell/retained/scratch allowance are prepaid.
 * A successful write promise is not transfer/consumption. Failed cells are never recycled.
 */
final class NativePhysicalOutputLedger {
    enum Lane { ACTION, TIME_SYNC, KEEPALIVE, CHUNK_BATCH }
    enum State { FREE, RESERVED, ACCEPTED_ENQUEUE, NATIVE_FLUSH_COMPLETE, CONSUMED }
    static final int ACTION_SLOTS=8, TIME_SLOTS=2, KEEPALIVE_SLOTS=2, CHUNK_SLOTS=0;
    // Includes 12 cells, original promise/outbound entries, listeners, small exact packet images,
    // origins, graph witness and comparison scratch. Chunk payloads are NOT admitted by this adapter.
    static final int RESERVATION_BYTES=24*1024;
    private final Cell[] cells=new Cell[ACTION_SLOTS+TIME_SLOTS+KEEPALIVE_SLOTS];
    private final int[] used=new int[Lane.values().length];
    private final int[] caps={ACTION_SLOTS,TIME_SLOTS,KEEPALIVE_SLOTS,CHUNK_SLOTS};
    private long nextSequence=1;
    private int live;
    private Throwable firstFailure;
    private boolean secondaryRecorded;
    NativePhysicalOutputLedger(){}
    static final class Cell {
        private final NativePhysicalOutputLedger owner;
        private State state=State.FREE;
        private Lane lane;
        private final long sequence;
        private long boundary;
        private Object packet,origin,promise;
        private boolean promiseObserved,flushObserved;
        private Cell(NativePhysicalOutputLedger owner,long sequence){this.owner=owner;this.sequence=sequence;}
        Object packet(){synchronized(owner){return packet;}}
        Object promise(){synchronized(owner){return promise;}}
        long sequence(){synchronized(owner){return sequence;}}
        State state(){synchronized(owner){return state;}}
    }
    synchronized void requireCapacity(Lane lane,int count){
        healthy();if(count<0||count>caps[lane.ordinal()]-used[lane.ordinal()])
            throw new SurvivalRefusal("Native physical output lane is full before callback/sequence admission");
    }
    synchronized Cell reserve(Lane lane,Object packet,Object exactOrigin){
        healthy();requireCapacity(lane,1);
        if(packet==null||exactOrigin==null)throw reject("Native output lacks its exact packet/origin");
        for(Cell cell:cells)if(cell!=null&&cell.state!=State.FREE&&cell.packet==packet)
            throw reject("Native output packet identity was enqueued twice");
        if(nextSequence==Long.MAX_VALUE)throw reject("Native output sequence exhausted");
        for(int i=0;i<cells.length;i++)if(cells[i]==null){
            // A fresh identity is the generation-bound lease; a retired handle can never alias a later packet.
            Cell cell=new Cell(this,nextSequence++);cells[i]=cell;
            cell.lane=lane;cell.packet=packet;cell.origin=exactOrigin;
            cell.state=State.RESERVED;used[lane.ordinal()]++;live++;return cell;
        }
        throw reject("Native output bounded cell accounting diverged");
    }
    synchronized Cell reserved(Object packet){
        healthy();for(Cell cell:cells)if(cell!=null&&cell.packet==packet&&cell.state==State.RESERVED)return cell;
        throw reject("Original write has no exact prepaid output reservation");
    }
    synchronized void writeEntered(Cell cell,Object originalPromise,long boundary){
        healthy();own(cell);
        if(cell.state!=State.RESERVED||cell.promise!=null||originalPromise==null||boundary<0)
            throw reject("Native unsafe write lacks its exact prepaid cell");
        for(Cell other:cells)if(other!=null&&other!=cell&&other.promise==originalPromise)
            throw reject("Two native packets share one original promise");
        cell.promise=originalPromise;cell.boundary=boundary;
    }
    synchronized void accepted(Cell cell,Object exactReturnedPromise){
        healthy();own(cell);
        if(cell.state!=State.RESERVED||cell.promise==null||cell.promise!=exactReturnedPromise)
            throw reject("Original write returned a foreign or replaced promise");
        cell.state=State.ACCEPTED_ENQUEUE;promote(cell);
    }
    /** Called at a qualified ORIGINAL flush entrance, never to request a flush. */
    synchronized void enterFlush(long boundary){
        healthy();if(boundary<=0)throw reject("Missing original flush boundary");
        for(Cell cell:cells)if(cell!=null&&cell.state==State.ACCEPTED_ENQUEUE&&cell.boundary==0)cell.boundary=boundary;
    }
    /** Observation of the exact returned original promise, before native failure listeners. */
    synchronized void promiseCompleted(Cell cell,Object exactPromise,boolean success,Throwable cause){
        own(cell);
        if(firstFailure!=null)return; // Keep retained cells; a secondary notification cannot release them.
        if(cell.promise!=exactPromise||cell.state!=State.RESERVED&&cell.state!=State.ACCEPTED_ENQUEUE&&cell.state!=State.NATIVE_FLUSH_COMPLETE)
            throw reject("Completion belongs to a replaced native promise/cell");
        if(cell.promiseObserved)throw reject("Original promise completion was observed twice");
        if(!success){fail(cause==null?new SurvivalRefusal("Original native promise failed without cause"):cause);return;}
        if(cell.boundary==0)throw reject("Native promise completed outside an authenticated original flush");
        cell.promiseObserved=true;promote(cell);
    }
    /** A successful return of the original call is independent of each promise's completion. */
    synchronized void exitFlush(long boundary){
        healthy();if(boundary<=0)throw reject("Missing original flush return boundary");
        for(Cell cell:cells)if(cell!=null&&cell.state!=State.FREE&&cell.boundary==boundary){cell.flushObserved=true;promote(cell);}
    }
    private void promote(Cell cell){
        if(cell!=null&&cell.state==State.ACCEPTED_ENQUEUE&&cell.promiseObserved&&cell.flushObserved)cell.state=State.NATIVE_FLUSH_COMPLETE;
    }
    synchronized Cell nextForConsumption(){
        healthy();Cell first=null;
        for(Cell cell:cells)if(cell!=null&&cell.state!=State.FREE&&(first==null||cell.sequence<first.sequence))first=cell;
        return first!=null&&first.state==State.NATIVE_FLUSH_COMPLETE?first:null;
    }
    /** Exact original readOutbound/peer-transfer evidence only. No count or queue-empty substitute. */
    synchronized void consumed(Cell cell,Object actualPacket,Object actualPromise){
        healthy();own(cell);
        if(nextForConsumption()!=cell||cell.state!=State.NATIVE_FLUSH_COMPLETE||cell.packet!=actualPacket||cell.promise!=actualPromise)
            throw reject("Native consumption is foreign, premature or out of order");
        cell.state=State.CONSUMED;
    }
    /** Adapter calls only after its packet/promise/listener/scratch aliases have physically drained. */
    synchronized void release(Cell cell){
        healthy();own(cell);if(cell.state!=State.CONSUMED)throw reject("Native output charge cannot release before exact physical drain");
        used[cell.lane.ordinal()]--;live--;
        for(int i=0;i<cells.length;i++)if(cells[i]==cell){cells[i]=null;break;}
        cell.packet=null;cell.origin=null;cell.promise=null;cell.lane=null;cell.boundary=0;
        cell.promiseObserved=false;cell.flushObserved=false;cell.state=State.FREE;
    }
    synchronized boolean ownsPacket(Object packet){
        for(Cell cell:cells)if(cell!=null&&cell.state!=State.FREE&&cell.packet==packet)return true;return false;
    }
    synchronized boolean ownsPromise(Object packet,Object promise){
        for(Cell cell:cells)if(cell!=null&&cell.state!=State.FREE&&cell.packet==packet&&cell.promise==promise)return true;return false;
    }
    synchronized int nativePending(){int count=0;for(Cell cell:cells)if(cell!=null&&cell.state!=State.FREE&&cell.promise!=null)count++;return count;}
    synchronized boolean settled(){healthy();return live==0;}
    /** Real reservation history, including flights already physically retired; not a readiness token. */
    synchronized long lineage(){healthy();return nextSequence-1;}
    synchronized int pending(){return live;}
    synchronized Throwable failure(){return firstFailure;}
    synchronized void fail(Throwable failure){
        if(failure==null)throw new IllegalArgumentException("Missing failure");
        if(firstFailure==null)firstFailure=failure;
        else if(firstFailure!=failure&&!secondaryRecorded){firstFailure.addSuppressed(failure);secondaryRecorded=true;}
    }
    synchronized void healthy(){
        if(firstFailure instanceof RuntimeException failure)throw failure;
        if(firstFailure instanceof Error failure)throw failure;
        if(firstFailure!=null){var refused=new SurvivalRefusal("Native physical output has failed");refused.initCause(firstFailure);throw refused;}
    }
    private void own(Cell cell){
        if(cell!=null&&cell.owner==this)for(Cell current:cells)if(current==cell)return;
        throw reject("Foreign or retired native output reservation identity");
    }
    private SurvivalRefusal reject(String text){var failure=new SurvivalRefusal(text);fail(failure);return failure;}
}
