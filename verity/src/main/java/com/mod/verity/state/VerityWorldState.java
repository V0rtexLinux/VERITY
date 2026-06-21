package com.mod.verity.state;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;

/**
 * Persistent server-side state for the full 5-stage Verity lifecycle + yandere system.
 *
 * Migrated from PersistentState (1.20.1 Yarn) → SavedData (26.1.2 Mojang mappings).
 */
public class VerityWorldState extends SavedData {

    private static final String KEY = "verity_world_state";

    public static final Factory<VerityWorldState> FACTORY = new Factory<>(
            VerityWorldState::new,
            (tag, registries) -> load(tag)
    );

    // ------------------------------------------------------------------ //
    //  Core progression                                                    //
    // ------------------------------------------------------------------ //
    private int     currentStage  = 1;
    private int     daysElapsed   = 0;
    private int     trustValue    = 50;
    private boolean hasEatenPizza = false;
    private double  playerHomeX   = 0;
    private double  playerHomeY   = 64;
    private double  playerHomeZ   = 0;
    private int     calmTicks     = 0;
    private boolean verityLost    = false;

    // ------------------------------------------------------------------ //
    //  Horror trigger flags                                                //
    // ------------------------------------------------------------------ //
    private boolean askedAboutEastVillage = false;
    private boolean leftVerity            = false;
    private boolean invitedFriendEarly    = false;
    private boolean madeAngry             = false;
    private int     dreadScore            = 0;
    private int     proximityTicks        = 0;
    private int     ignoredTicks          = 0;

    // ------------------------------------------------------------------ //
    //  Yandere system                                                      //
    // ------------------------------------------------------------------ //
    private int     attachmentScore = 30;
    private int     jealousyScore   = 0;
    private long    lonelinessTicks = 0;
    private boolean yandereMode     = false;
    private int     rejectionCount  = 0;

    // ------------------------------------------------------------------ //
    //  Factory / loading                                                   //
    // ------------------------------------------------------------------ //
    public static VerityWorldState getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, KEY);
    }

    private static VerityWorldState load(CompoundTag nbt) {
        VerityWorldState s = new VerityWorldState();
        s.currentStage          = nbt.getInt("currentStage");
        s.daysElapsed           = nbt.getInt("daysElapsed");
        s.trustValue            = nbt.getInt("trustValue");
        s.hasEatenPizza         = nbt.getBoolean("hasEatenPizza");
        s.playerHomeX           = nbt.getDouble("playerHomeX");
        s.playerHomeY           = nbt.getDouble("playerHomeY");
        s.playerHomeZ           = nbt.getDouble("playerHomeZ");
        s.calmTicks             = nbt.getInt("calmTicks");
        s.verityLost            = nbt.getBoolean("verityLost");
        s.askedAboutEastVillage = nbt.getBoolean("askedAboutEastVillage");
        s.leftVerity            = nbt.getBoolean("leftVerity");
        s.invitedFriendEarly    = nbt.getBoolean("invitedFriendEarly");
        s.madeAngry             = nbt.getBoolean("madeAngry");
        s.dreadScore            = nbt.getInt("dreadScore");
        s.proximityTicks        = nbt.getInt("proximityTicks");
        s.ignoredTicks          = nbt.getInt("ignoredTicks");
        s.attachmentScore       = nbt.getInt("attachmentScore");
        s.jealousyScore         = nbt.getInt("jealousyScore");
        s.lonelinessTicks       = nbt.getLong("lonelinessTicks");
        s.yandereMode           = nbt.getBoolean("yandereMode");
        s.rejectionCount        = nbt.getInt("rejectionCount");
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putInt("currentStage",              currentStage);
        nbt.putInt("daysElapsed",               daysElapsed);
        nbt.putInt("trustValue",                trustValue);
        nbt.putBoolean("hasEatenPizza",         hasEatenPizza);
        nbt.putDouble("playerHomeX",            playerHomeX);
        nbt.putDouble("playerHomeY",            playerHomeY);
        nbt.putDouble("playerHomeZ",            playerHomeZ);
        nbt.putInt("calmTicks",                 calmTicks);
        nbt.putBoolean("verityLost",            verityLost);
        nbt.putBoolean("askedAboutEastVillage", askedAboutEastVillage);
        nbt.putBoolean("leftVerity",            leftVerity);
        nbt.putBoolean("invitedFriendEarly",    invitedFriendEarly);
        nbt.putBoolean("madeAngry",             madeAngry);
        nbt.putInt("dreadScore",                dreadScore);
        nbt.putInt("proximityTicks",            proximityTicks);
        nbt.putInt("ignoredTicks",              ignoredTicks);
        nbt.putInt("attachmentScore",           attachmentScore);
        nbt.putInt("jealousyScore",             jealousyScore);
        nbt.putLong("lonelinessTicks",          lonelinessTicks);
        nbt.putBoolean("yandereMode",           yandereMode);
        nbt.putInt("rejectionCount",            rejectionCount);
        return nbt;
    }

    // ------------------------------------------------------------------ //
    //  Stage management                                                    //
    // ------------------------------------------------------------------ //
    public int getCurrentStage() { return currentStage; }
    public void setCurrentStage(int stage) {
        this.currentStage = Math.max(1, Math.min(5, stage));
        setDirty();
    }
    public void advanceStage() {
        if (currentStage < 5) { currentStage++; setDirty(); }
    }

    // ------------------------------------------------------------------ //
    //  Time & trust                                                        //
    // ------------------------------------------------------------------ //
    public int getDaysElapsed()  { return daysElapsed; }
    public void incrementDay()   { daysElapsed++; setDirty(); }

    public int getTrustValue()   { return trustValue; }
    public void adjustTrust(int delta) {
        trustValue = Math.max(0, Math.min(100, trustValue + delta));
        setDirty();
    }

    // ------------------------------------------------------------------ //
    //  Food memory                                                         //
    // ------------------------------------------------------------------ //
    public boolean hasEatenPizza()            { return hasEatenPizza; }
    public void setHasEatenPizza(boolean v)   { this.hasEatenPizza = v; setDirty(); }

    // ------------------------------------------------------------------ //
    //  Player home                                                         //
    // ------------------------------------------------------------------ //
    public double getPlayerHomeX() { return playerHomeX; }
    public double getPlayerHomeY() { return playerHomeY; }
    public double getPlayerHomeZ() { return playerHomeZ; }
    public void setPlayerHome(double x, double y, double z) {
        playerHomeX = x; playerHomeY = y; playerHomeZ = z;
        setDirty();
    }

    // ------------------------------------------------------------------ //
    //  Underground good ending                                             //
    // ------------------------------------------------------------------ //
    public int getCalmTicks()             { return calmTicks; }
    public void incrementCalmTicks()      { calmTicks++; setDirty(); }
    public void resetCalmTicks()          { if (calmTicks != 0) { calmTicks = 0; setDirty(); } }
    public boolean isVerityLost()         { return verityLost; }
    public void setVerityLost(boolean v)  { verityLost = v; setDirty(); }

    // ------------------------------------------------------------------ //
    //  Horror triggers                                                     //
    // ------------------------------------------------------------------ //
    public boolean hasAskedAboutEastVillage() { return askedAboutEastVillage; }
    public void triggerEastVillage() {
        if (!askedAboutEastVillage) {
            askedAboutEastVillage = true;
            addDread(30);
            adjustJealousy(20);
            setDirty();
        }
    }

    public boolean hasLeftVerity() { return leftVerity; }
    public void triggerLeftVerity() {
        if (!leftVerity) {
            leftVerity = true;
            addDread(20);
            adjustAttachment(-15);
            setDirty();
        }
    }

    public boolean hasInvitedFriendEarly() { return invitedFriendEarly; }
    public void triggerInvitedFriendEarly() {
        if (!invitedFriendEarly) {
            invitedFriendEarly = true;
            addDread(25);
            adjustJealousy(30);
            setDirty();
        }
    }

    public boolean hasMadeAngry() { return madeAngry; }
    public void triggerMadeAngry() {
        if (!madeAngry) {
            madeAngry = true;
            addDread(35);
            adjustAttachment(-25);
            rejectionCount++;
            setDirty();
        }
    }

    public int getDreadScore()      { return dreadScore; }
    public void addDread(int n)     { dreadScore = Math.min(100, dreadScore + n); setDirty(); }
    public boolean isDreadMaxed()   { return dreadScore >= 100; }

    public int getProximityTicks()  { return proximityTicks; }
    public void setProximityTicks(int v) {
        if (proximityTicks != v) { proximityTicks = v; setDirty(); }
    }

    public int getIgnoredTicks()        { return ignoredTicks; }
    public void incrementIgnoredTicks() { ignoredTicks++; setDirty(); }
    public void resetIgnoredTicks()     { if (ignoredTicks != 0) { ignoredTicks = 0; setDirty(); } }

    // ------------------------------------------------------------------ //
    //  Yandere system                                                      //
    // ------------------------------------------------------------------ //
    public int getAttachmentScore() { return attachmentScore; }

    public void adjustAttachment(int delta) {
        int prev = attachmentScore;
        attachmentScore = Math.max(0, Math.min(100, attachmentScore + delta));
        if (attachmentScore >= 100 && !yandereMode) {
            yandereMode = true;
            addDread(20);
        }
        if (attachmentScore < 30 && prev >= 30) {
            addDread(10);
        }
        setDirty();
    }

    public int getJealousyScore() { return jealousyScore; }
    public void adjustJealousy(int delta) {
        jealousyScore = Math.max(0, Math.min(100, jealousyScore + delta));
        if (jealousyScore >= 80) addDread(5);
        setDirty();
    }

    public long getLonelinessTicks() { return lonelinessTicks; }
    public void addLonelinessTicks(long ticks) {
        lonelinessTicks += ticks;
        if (lonelinessTicks > 72000) adjustAttachment(5);
        setDirty();
    }
    public void resetLonelinessTicks() { lonelinessTicks = 0; setDirty(); }

    public boolean isYandereMode()  { return yandereMode; }

    public int getRejectionCount()  { return rejectionCount; }
    public void incrementRejectionCount() {
        rejectionCount++;
        addDread(10 * rejectionCount);
        adjustAttachment(-10);
        setDirty();
    }

    public void onPositiveInteraction() {
        adjustAttachment(+3);
        adjustJealousy(-2);
        resetIgnoredTicks();
        setDirty();
    }

    public void tickLoneliness() {
        lonelinessTicks++;
        if (lonelinessTicks % 1200 == 0) {
            adjustAttachment(+2);
            adjustJealousy(+1);
        }
        setDirty();
    }
}
