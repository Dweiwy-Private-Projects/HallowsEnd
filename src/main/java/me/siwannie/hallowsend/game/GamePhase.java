package me.siwannie.hallowsend.game;

public enum GamePhase {
    WAITING("Waiting", -1),
    INTRO_CUTSCENE("Introduction", 60),
    PHASE_1_CALM("The Eerie Calm", 900),
    PHASE_2_AWAKENING("The Awakening", 1200),
    PHASE_3_BLOOD_MOON("The Blood Moon", 1200),
    PHASE_4_RAMPAGE("The Harbinger's Rampage", 120),
    PHASE_5_LMS("The Final Reckoning", -1);
    private final String displayName;
    private int durationSeconds;

    GamePhase(String displayName, int durationSeconds) {
        this.displayName = displayName;
        this.durationSeconds = durationSeconds;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public GamePhase next() {
        switch (this) {
            case INTRO_CUTSCENE: return PHASE_1_CALM;
            case PHASE_1_CALM: return PHASE_2_AWAKENING;
            case PHASE_2_AWAKENING: return PHASE_3_BLOOD_MOON;
            case PHASE_3_BLOOD_MOON: return PHASE_4_RAMPAGE;
            case PHASE_4_RAMPAGE: return PHASE_5_LMS;
            case PHASE_5_LMS:
            case WAITING:
            default:
                return WAITING;
        }
    }
}
