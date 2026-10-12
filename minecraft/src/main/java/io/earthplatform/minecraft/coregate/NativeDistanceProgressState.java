package io.earthplatform.minecraft.coregate;

import java.util.Arrays;
import static io.earthplatform.minecraft.coregate.NativeDistanceProgressWatchdog.Lane;

/** Owner-thread transport state. The watchdog, held episode and semantic credits survive every attempt. */
final class NativeDistanceProgressState {
    /** Clock data only may cross threads. Protocol state and physical owners never do. */
    static final class ActualPauseClock {
        private long observed, active;
        private boolean paused;
        private String failure;
        ActualPauseClock(long now,boolean paused){observed=now;this.paused=paused;}
        synchronized void edge(long now,boolean nextPaused){sample(now);if(failure==null)paused=nextPaused;}
        synchronized long sample(long now){
            if(failure!=null)return active;
            try{long elapsed=Math.subtractExact(now,observed);if(elapsed<0){failure="pause-clock-regression";return active;}if(!paused)active=Math.addExact(active,elapsed);observed=now;}
            catch(ArithmeticException overflow){failure="pause-clock-overflow";}
            return active;
        }
        synchronized String failure(){return failure;}
    }
    record ClockSample(long activeNanos,String failure) {}
    private static Object clockServer,clockConnection;
    private static long clockEpisode;
    private static ActualPauseClock pauseClock;
    static synchronized boolean bindPauseClock(Object server,Object connection,long episode,boolean paused){
        if(server==null||connection==null||episode<=0)throw new IllegalArgumentException("Exact integrated pause-clock owner required");
        if(pauseClock!=null){if(clockServer!=server||clockConnection!=connection||clockEpisode!=episode)return false;pauseClock.edge(System.nanoTime(),paused);return true;}
        clockServer=server;clockConnection=connection;clockEpisode=episode;pauseClock=new ActualPauseClock(System.nanoTime(),paused);return true;
    }
    static synchronized void observeActualPause(Object server,Object connection,long episode,boolean paused){
        if(pauseClock!=null&&clockServer==server&&clockConnection==connection&&clockEpisode==episode)pauseClock.edge(System.nanoTime(),paused);
    }
    static synchronized void revokePauseClock(Object server,Object connection,long episode){
        if(clockServer==server&&clockConnection==connection&&clockEpisode==episode){pauseClock=null;clockServer=clockConnection=null;clockEpisode=0;}
    }
    static synchronized ClockSample serverClock(Object server,long episode){
        if(pauseClock==null||clockServer!=server||clockEpisode!=episode)return new ClockSample(0,"pause-clock-owner-changed");
        return new ClockSample(pauseClock.sample(System.nanoTime()),pauseClock.failure());
    }
    final NativeDistanceProgressWatchdog watchdog;
    final boolean server;
    NativeDistanceChangePayload request;
    long actorEpoch, resources, publication;
    String digest="", plan="";
    private final long[] completed=new long[NativeDistanceProgressWatchdog.LANE_COUNT];
    private long observation, lastSentNanos=Long.MIN_VALUE, lastServerTick, lastAckNanos=Long.MIN_VALUE;
    boolean failureReported;
    private long lastCheckpointNanos=Long.MIN_VALUE;
    private long[] lastCheckpointRaw;
    private String lastCheckpointIdentity="";
    private int checkpointsWritten;


    NativeDistanceProgressState(NativeDistanceChangePayload request,boolean server,long now,boolean paused,long serverTick){
        this.server=server;this.request=request;lastServerTick=serverTick;
        watchdog=new NativeDistanceProgressWatchdog(request.epoch(),request.selected(),now,paused,true);
        bind();
    }
    void request(NativeDistanceChangePayload next){
        if(watchdog.failed())return;
        if(next.epoch()!=request.epoch()||next.serial()<request.serial())throw new IllegalArgumentException("Different continuation episode");
        if(next.serial()==request.serial()){if(!next.sameSelection(request))throw new IllegalArgumentException("Changed continuation tuple");return;}
        request=next;actorEpoch=resources=publication=0;digest=plan="";Arrays.fill(completed,0);bind();
    }
    boolean header(long actor,long resource,long pub,String hash,String job){
        if(watchdog.failed())return false;
        if(actorEpoch!=0&&actor!=actorEpoch)return false;
        if(resources!=0&&resource!=resources)return false;
        if(publication!=0&&pub!=publication)return false;
        if(!digest.isEmpty()&&!hash.equals(digest))return false;
        if(!plan.isEmpty()&&!job.equals(plan))return false;
        if(!watchdog.bindAttempt(request.epoch(),request.serial(),request.selected(),request.effective(),request.serverLimit(),actor,resource,pub,hash,job))return false;
        actorEpoch=actor;resources=resource;publication=pub;digest=hash;plan=job;return true;
    }
    private void bind(){watchdog.bindAttempt(request.epoch(),request.serial(),request.selected(),request.effective(),request.serverLimit(),actorEpoch,resources,publication,digest,plan);}
    void credit(Lane lane,long value){
        if(lane.server()!=server)throw new IllegalArgumentException("Wrong producer lane");
        if(value<completed[lane.ordinal()]){watchdog.fail("local-progress-rollback:"+lane);return;}
        if(watchdog.creditLane(lane,value))completed[lane.ordinal()]=value;
        else if(!watchdog.failed())completed[lane.ordinal()]=value;
    }
    void increment(Lane lane){credit(lane,completed[lane.ordinal()]+1);}
    void observe(long now,boolean paused){watchdog.observe(now,paused);}
    void serverTick(long tick,ClockSample clock){
        if(!server)throw new IllegalStateException("Client cannot supply server clock");
        if(clock.failure()!=null){watchdog.fail(clock.failure());return;}
        if(tick<lastServerTick){watchdog.fail("server-tick-regression");return;}
        watchdog.observe(clock.activeNanos(),false); // Already pause-subtracted wall time, never tick-equivalent time.
        lastServerTick=tick;
    }
    boolean accept(NativeDistanceProgressPayload payload){
        if(payload.server()==server)return false;
        boolean accepted=watchdog.accept(payload);
        if(accepted&&server)watchdog.observePeer(payload.observation());
        return accepted;
    }
    NativeDistanceProgressPayload packet(long peerTick){
        if(watchdog.failed())return null;
        if(observation==Long.MAX_VALUE){watchdog.fail("observation-overflow");return null;}
        return new NativeDistanceProgressPayload(watchdog.episode(),request.epoch(),request.serial(),request.selected(),request.effective(),request.serverLimit(),actorEpoch,resources,publication,digest,plan,++observation,peerTick,server,completed);
    }
    boolean sendDue(long now){if(lastSentNanos==Long.MIN_VALUE||now-lastSentNanos>=1_000_000_000L){lastSentNanos=now;return true;}return false;}
    boolean ackDue(long now){if(lastAckNanos==Long.MIN_VALUE||now-lastAckNanos>=1_000_000_000L){lastAckNanos=now;return true;}return false;}
    boolean matchesRequest(NativeDistanceProgressPayload p){return p.episode()==watchdog.episode()&&p.requestEpoch()==request.epoch()&&p.serial()==request.serial()&&p.selected()==request.selected()&&p.effective()==request.effective()&&p.serverLimit()==request.serverLimit();}
    /** Bounded evidence only. Polling, logging and phase changes grant no work or deadline credit. */
    boolean checkpointDue(long now,boolean pauseEdge){
        if(checkpointsWritten>=512)return false;
        long[] raw=watchdog.rawVector();String identity=request.serial()+":"+actorEpoch+":"+resources+":"+publication+":"+digest+":"+plan;
        boolean changed=!Arrays.equals(raw,lastCheckpointRaw)||!identity.equals(lastCheckpointIdentity);
        if(!pauseEdge&&!changed)return false;
        if(lastCheckpointNanos!=Long.MIN_VALUE&&now-lastCheckpointNanos<(pauseEdge?1_000_000_000L:5_000_000_000L))return false;
        lastCheckpointNanos=now;lastCheckpointRaw=raw;lastCheckpointIdentity=identity;checkpointsWritten++;return true;
    }
    NativeDistanceProgressWatchdog.Checkpoint checkpoint(){return watchdog.checkpoint();}
}
