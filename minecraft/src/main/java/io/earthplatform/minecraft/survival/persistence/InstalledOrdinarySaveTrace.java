package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;

/** Exact synchronous native sequence. Neither this trace nor its terminal state grants admission. */
final class InstalledOrdinarySaveTrace {
    enum Step { NEW, CACHING, CACHED, PLAYER, PLAYER_DONE, STATS, STATS_WRITTEN, STATS_DONE,
        ADVANCEMENTS, ADVANCEMENTS_WRITTEN, ADVANCEMENTS_DONE, LIST_DONE,
        LEVEL, LEVEL_WRITTEN, LEVEL_REPLACED, LEVEL_DONE, VERIFIED, FAILED }
    private Step step=Step.NEW;
    private Throwable failure;
    Step step(){return step;}
    Throwable failure(){return failure;}
    void cacheEnter(){next(Step.NEW,Step.CACHING);}
    void cacheDone(){next(Step.CACHING,Step.CACHED);}
    void playerEnter(){next(Step.CACHED,Step.PLAYER);}
    void playerDone(){next(Step.PLAYER,Step.PLAYER_DONE);}
    void statsEnter(){next(Step.PLAYER_DONE,Step.STATS);}
    void statsWritten(){next(Step.STATS,Step.STATS_WRITTEN);}
    void statsDone(){next(Step.STATS_WRITTEN,Step.STATS_DONE);}
    void advancementsEnter(){next(Step.STATS_DONE,Step.ADVANCEMENTS);}
    void advancementsWritten(){next(Step.ADVANCEMENTS,Step.ADVANCEMENTS_WRITTEN);}
    void advancementsDone(){next(Step.ADVANCEMENTS_WRITTEN,Step.ADVANCEMENTS_DONE);}
    void listDone(){next(Step.ADVANCEMENTS_DONE,Step.LIST_DONE);}
    void levelEnter(){next(Step.LIST_DONE,Step.LEVEL);}
    void levelWritten(){next(Step.LEVEL,Step.LEVEL_WRITTEN);}
    void levelReplaced(boolean succeeded){if(!succeeded)throw refuse("Native level.dat replacement did not succeed");next(Step.LEVEL_WRITTEN,Step.LEVEL_REPLACED);}
    void levelDone(){next(Step.LEVEL_REPLACED,Step.LEVEL_DONE);}
    void verified(){next(Step.LEVEL_DONE,Step.VERIFIED);}
    void requireVerified(){if(step!=Step.VERIFIED || failure!=null)throw refuse("Installed ordinary save lacks complete verified native evidence");}
    void poison(Throwable cause){if(failure==null)failure=cause;step=Step.FAILED;}
    private void next(Step expected,Step target){if(step!=expected || failure!=null)throw refuse("Installed ordinary native save sequence differs: "+step+" expected "+expected);step=target;}
    private SurvivalRefusal refuse(String message){var refused=new SurvivalRefusal(message);poison(refused);return refused;}
}
