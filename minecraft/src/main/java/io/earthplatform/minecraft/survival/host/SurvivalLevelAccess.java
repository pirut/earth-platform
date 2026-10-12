package io.earthplatform.minecraft.survival.host;

/** Explicit identity-bound server Level bridge; detached Levels remain fenced. */
public interface SurvivalLevelAccess {
    SurvivalLevelBinding earthplatform$survivalBinding();
    void earthplatform$bindSurvival(SurvivalLevelBinding binding);
    void earthplatform$unbindSurvival(SurvivalLevelBinding expected);
}
