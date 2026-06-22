package com.mod.verity.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persistent server-side state for the full 5-stage Verity lifecycle + yandere system.
 *
 * Migrated from PersistentState (1.20.1 Yarn) → SavedData + SavedDataType + Codec (26.1.2).
 * SavedData.Factory was removed in 26.1; use SavedDataType with a RecordCodecBuilder codec.
 */
public class VerityWorldState extends SavedData {

    private static final Identifier KEY = Identifier.fromNamespaceAndPath("verity", "world_state");

    // ------------------------------------------------------------------ //
    //  Nested record to hold yandere/extra fields (RecordCodecBuilder     //
    //  supports up to 16 fields per group; extras are nested here)        //
    // ------------------------------------------------------------------ //
    record YandereState(int ignoredTicks, int attachmentScore, int jealousyScore,
                        long lonelinessTicks, boolean yandereMode, int rejectionCount) {}

    private static final Codec<YandereState> YANDERE_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("ignoredTicks",    0).forGetter(YandereState::ignoredTicks),
            Codec.INT.optionalFieldOf("attachmentScore", 30).forGetter(YandereState::attachmentScore),
            Codec.INT.optionalFieldOf("jealousyScore",   0).forGetter(YandereState::jealousyScore),
            Codec.LONG.optionalFieldOf("lonelinessTicks", 0L).forGetter(YandereState::lonelinessTicks),
            Codec.BOOL.optionalFieldOf("yandereMode",    false).forGetter(YandereState::yandereMode),
            Codec.INT.optionalFieldOf("rejectionCount",  0).forGetter(YandereState::rejectionCount)
    ).apply(i, YandereState::new));

    private static final Codec<VerityWorldState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("currentStage",           1).forGetter(s -> s.currentStage),
            Codec.INT.optionalFieldOf("daysElapsed",            0).forGetter(s -> s.daysElapsed),
            Codec.INT.optionalFieldOf("trustValue",            50).forGetter(s -> s.trustValue),
            Codec.BOOL.optionalFieldOf("hasEatenPizza",     false).forGetter(s -> s.hasEatenPizza),
            Codec.DOUBLE.optionalFieldOf("playerHomeX",       0.0).forGetter(s -> s.playerHomeX),
            Codec.DOUBLE.optionalFieldOf("playerHomeY",      64.0).forGetter(s -> s.playerHomeY),
            Codec.DOUBLE.optionalFieldOf("playerHomeZ",       0.0).forGetter(s -> s.playerHomeZ),
            Codec.INT.optionalFieldOf("calmTicks",             0).forGetter(s -> s.calmTicks),
            Codec.BOOL.optionalFieldOf("verityLost",       false).forGetter(s -> s.verityLost),
            Codec.BOOL.optionalFieldOf("askedAboutEastVillage", false).forGetter(s -> s.askedAboutEastVillage),
            Codec.BOOL.optionalFieldOf("leftVerity",       false).forGetter(s -> s.leftVerity),
            Codec.BOOL.optionalFieldOf("invitedFriendEarly", false).forGetter(s -> s.invitedFriendEarly),
            Codec.BOOL.optionalFieldOf("madeAngry",        false).forGetter(s -> s.madeAngry),
            Codec.INT.optionalFieldOf("dreadScore",            0).forGetter(s -> s.dreadScore),
            Codec.INT.optionalFieldOf("proximityTicks",        0).forGetter(s -> s.proximityTicks),
            YANDERE_CODEC.fieldOf("yandere").forGetter(s -> new YandereState(
                    s.ignoredTicks, s.attachmentScore, s.jealousyScore,
                    s.lonelinessTicks, s.yandereMode, s.rejectionCount))
    ).apply(i, (currentStage, daysElapsed, trustValue, hasEatenPizza,
                playerHomeX, playerHomeY, playerHomeZ, calmTicks, verityLost,
                askedAboutEastVillage, leftVerity, invitedFriendEarly, madeAngry,
                dreadScore, proximityTicks, yandere) -> {
        VerityWorldState s = new VerityWorldState();
        s.currentStage          = currentStage;
        s.daysElapsed           = daysElapsed;
        s.trustValue            = trustValue;
        s.hasEatenPizza         = hasEatenPizza;
        s.playerHomeX           = playerHomeX;
        s.playerHomeY           = playerHomeY;
        s.playerHomeZ           = playerHomeZ;
        s.calmTicks             = calmTicks;
        s.verityLost            = verityLost;
        s.askedAboutEastVillage = askedAboutEastVillage;
        s.leftVerity            = leftVerity;
        s.invitedFriendEarly    = invitedFriendEarly;
        s.madeAngry             = madeAngry;
        s.dreadScore            = dreadScore;
        s.proximityTicks        = proximityTicks;
        s.ignoredTicks          = yandere.ignoredTicks();
        s.attachmentScore       = yandere.attachmentScore();
        s.jealousyScore         = yandere.jealousyScore();
        s.lonelinessTicks       = yandere.lonelinessTicks();
        s.yandereMode           = yandere.yandereMode();
        s.rejectionCount        = yandere.rejectionCount();
        return s;
    }));

    public static final SavedDataType<VerityWorldState> TYPE = new SavedDataType<>(
            KEY,
            ctx -> new VerityWorldState(),
            ctx -> CODEC,
            null
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
        return level.getDataStorage().computeIfAbsent(TYPE);
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
