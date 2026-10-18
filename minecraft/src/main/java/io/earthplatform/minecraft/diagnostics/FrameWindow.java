package io.earthplatform.minecraft.diagnostics;

import java.util.Arrays;

/** Fixed-memory frame distribution: exact mean/count/max and 1 ms upper-bound quantiles. */
public final class FrameWindow {
    private static final int MAX_MS=2000;
    private final long[] bins=new long[MAX_MS+1];
    private long count,invalid,maxNs,over33,over40,over50,over100;
    private double sumMs;
    public void add(long nanos) {
        if(nanos<=0){invalid++;return;}
        long ms=nanos/1_000_000+(nanos%1_000_000==0?0:1);
        bins[(int)Math.min(MAX_MS,ms)]++;count++;sumMs+=nanos/1e6;maxNs=Math.max(maxNs,nanos);
        if(nanos>33_333_333L)over33++;if(nanos>40_000_000L)over40++;
        if(nanos>50_000_000L)over50++;if(nanos>100_000_000L)over100++;
    }
    public record Summary(long frames,long invalid,double meanMs,double averageFps,int p95UpperMs,int p99UpperMs,
                          boolean p95Capped,boolean p99Capped,double maxMs,long over33,long over40,long over50,long over100) {}
    public Summary take() {
        int p95=quantile(.95),p99=quantile(.99);
        var result=new Summary(count,invalid,count==0?0:sumMs/count,sumMs==0?0:count*1000/sumMs,
                p95,p99,p95==MAX_MS,p99==MAX_MS,maxNs/1e6,over33,over40,over50,over100);
        Arrays.fill(bins,0);count=invalid=maxNs=over33=over40=over50=over100=0;sumMs=0;
        return result;
    }
    private int quantile(double fraction) {
        if(count==0)return 0;long wanted=(long)Math.ceil(count*fraction),seen=0;
        for(int i=0;i<bins.length;i++){seen+=bins[i];if(seen>=wanted)return i;}
        return MAX_MS;
    }
}
