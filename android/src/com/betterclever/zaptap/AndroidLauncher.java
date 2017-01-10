package com.betterclever.zaptap;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.chartboost.sdk.Chartboost;
import com.chartboost.sdk.Libraries.CBLogging;
import com.google.ads.mediation.chartboost.ChartboostAdapter;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.reward.RewardItem;
import com.google.android.gms.ads.reward.RewardedVideoAd;
import com.google.android.gms.ads.reward.RewardedVideoAdListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.games.Games;
import com.google.android.gms.games.Player;
import com.google.android.gms.games.leaderboard.LeaderboardScore;
import com.google.android.gms.games.leaderboard.LeaderboardVariant;
import com.google.android.gms.games.leaderboard.Leaderboards;
import com.google.example.games.basegameutils.GameHelper;

public class AndroidLauncher extends AndroidApplication implements PlayGameServices, RewardedVideoAdListener {

    private static final String TAG = AndroidLauncher.class.getSimpleName();
    private GameHelper gameHelper;
    private final static int requestCode = 1;
    Preferences preferences;

    private RewardedVideoAd mRewardedVideoAd;

    private static String AD_UNIT_ID;
    private static String APP_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AD_UNIT_ID = getString(R.string.adunit_id);
        APP_ID = getString(R.string.app_id);
        
        MobileAds.initialize(this,APP_ID);
        mRewardedVideoAd = MobileAds.getRewardedVideoAdInstance(this);
        mRewardedVideoAd.setRewardedVideoAdListener(this);
        //loadRewardedVideoAd();
        Chartboost.setLoggingLevel(CBLogging.Level.ALL);
        Chartboost.onCreate(this);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        initialize(new ZapTapGame(this), config);

        gameHelper = new GameHelper(this, GameHelper.CLIENT_GAMES);
        gameHelper.enableDebugLog(false);

        preferences = Gdx.app.getPreferences(Constants.PREF_KEY);

        GameHelper.GameHelperListener gameHelperListener = new GameHelper.GameHelperListener() {
            @Override
            public void onSignInFailed() {
            }

            @Override
            public void onSignInSucceeded() {
                storePlayerData();
            }


        };

        gameHelper.setup(gameHelperListener);
        submitAllScores();

        loadRewardedVideoAd();

    }

    private void storePlayerData() {

        storeUserId();
        submitAllScores();

        for (int i = 0; i < 4; i++) {
            final int mode = i;
            Games.Leaderboards.loadCurrentPlayerLeaderboardScore(gameHelper.getApiClient(),
                    getStringByMode(mode),
                    LeaderboardVariant.TIME_SPAN_ALL_TIME,
                    LeaderboardVariant.COLLECTION_PUBLIC).
                    setResultCallback(new ResultCallback<Leaderboards.LoadPlayerScoreResult>() {

                        @Override
                        public void onResult(final Leaderboards.LoadPlayerScoreResult scoreResult) {
                            // if (isScoreResultValid(scoreResult)) {
                            LeaderboardScore c = scoreResult.getScore();
                            if(c!=null) {
                                int modef = mode;
                                Gdx.app.log("score + mode", c.getRawScore() + "  " + modef);
                                if (c.getRawScore() > preferences.getInteger(getStringByMode(modef))) {
                                    preferences.putInteger(getStringByMode(modef), (int) c.getRawScore()).flush();
                                }
                                unlockByCurrentScore((int) c.getRawScore(), modef);
                            }
                        }
                    });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        gameHelper.onStart(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        gameHelper.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        gameHelper.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void signIn() {
        try {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    gameHelper.beginUserInitiatedSignIn();
                }
            });
        } catch (Exception e) {
            Gdx.app.log("MainActivity", "Log in failed: " + e.getMessage() + ".");
        }
    }

    @Override
    public void signOut() {
        try {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    gameHelper.signOut();
                }
            });
        } catch (Exception e) {
            Gdx.app.log("MainActivity", "Log out failed: " + e.getMessage() + ".");
        }
    }

    @Override
    public void rateGame() {
        String str = "Your PlayStore Link";
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(str)));
    }

    @Override
    public void unlockAchievement() {
        int playCount = preferences.getInteger(Constants.PLAY_COUNT);
        if (playCount >= 50) {
            Games.Achievements.unlock(gameHelper.getApiClient(),
                    getString(R.string.achievement_addicted));
        } else if (playCount >= 200) {
            Games.Achievements.unlock(gameHelper.getApiClient(),
                    getString(R.string.achievement_boredom_killer));
        }
    }

    @Override
    public void submitScore(int highScore, int mode) {
        showRewardedVideo();
        if (isSignedIn()) {
            String storedPlayerID = preferences.getString("playerid", "");
            String currentPlayerID = Games.Players.getCurrentPlayer(gameHelper.getApiClient()).getPlayerId();

            String modeID = getStringByMode(mode);

            if (storedPlayerID.equals("") || storedPlayerID.equals(currentPlayerID)) {
                if (highScore > preferences.getInteger(modeID)) {
                    preferences.putInteger(modeID, highScore).flush();
                    submitAllScores();
                }
                Games.Leaderboards.submitScore(gameHelper.getApiClient(),
                        modeID, highScore);

            } else {
                resetScores();
                if (highScore > preferences.getInteger(modeID)) {
                    preferences.putInteger(modeID, highScore).flush();
                    submitAllScores();
                    Games.Leaderboards.submitScore(gameHelper.getApiClient(),
                            modeID, highScore);
                }
            }

            checkForAchievements(highScore, mode);

            storeUserId();
            Gdx.app.log(TAG, String.valueOf(highScore));
        }
    }

    private void unlockByCurrentScore(int score, int mode) {
        if (mode == Constants.EASY_MODE) {
            if (score >= 50) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_zap_ninja_level_1));
            }
            if (score >= 100) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_zap_blaster_level_1));
            }
            if (score >= 200) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_unstoppable_zapper_level_1));
            }
        } else if (mode == Constants.MEDIUM_MODE) {
            if (score >= 50) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_zap_ninja_level_2));
            }
            if (score >= 100) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_zap_blaster_level_2));
            }
            if (score >= 200) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_unstoppable_zapper_level_2));
            }
        } else if (mode == Constants.HARD_MODE) {
            if (score >= 50) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_zap_ninja_level_3));
            }
            if (score >= 100) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_zap_blaster_level_3));
            }
            if (score >= 200) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_unstoppable_zapper_level_3));
            }
        } else if (mode == Constants.INSANE_MODE) {
            if (score >= 50) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_ultimate_zap_ninja));
            }
            if (score >= 100) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_ultimate_blaster));
            }
            if (score >= 200) {
                Games.Achievements.unlock(gameHelper.getApiClient(),
                        getString(R.string.achievement_insane_zapper));
            }
        }
    }

    private void checkForAchievements(int score, final int mode) {


        Games.Leaderboards.loadCurrentPlayerLeaderboardScore(gameHelper.getApiClient(),
                getStringByMode(mode),
                LeaderboardVariant.TIME_SPAN_ALL_TIME,
                LeaderboardVariant.COLLECTION_PUBLIC).
                setResultCallback(new ResultCallback<Leaderboards.LoadPlayerScoreResult>() {

                    @Override
                    public void onResult(final Leaderboards.LoadPlayerScoreResult scoreResult) {
                        // if (isScoreResultValid(scoreResult)) {
                        LeaderboardScore c = scoreResult.getScore();
                        if(c!=null) {
                            Gdx.app.log("score + mode", c.getRawScore() + "  " + mode);
                            if (c.getRawScore() > preferences.getInteger(getStringByMode(mode))) {
                                preferences.putInteger(getStringByMode(mode), (int) c.getRawScore()).flush();
                            }
                            unlockByCurrentScore((int) c.getRawScore(), mode);
                        }
                    }
                });

        unlockByCurrentScore(score, mode);

    }

    private void resetScores() {
        if (isSignedIn()) {
            for (int i = 0; i < 4; i++) {
                String modeId = getStringByMode(i);
                preferences.putInteger(modeId, 0).flush();
            }

        }
    }

    private void storeUserId() {
        String playerID = Games.Players.getCurrentPlayer(gameHelper.getApiClient()).getPlayerId();
        if(! preferences.getString("playerid").equals(playerID)) {
            preferences.putString("playerid", playerID).flush();
            resetScores();
        }
    }

    public void submitAllScores() {
        if (isSignedIn() == true) {
            for (int i = 0; i < 4; i++) {
                String modeId = getStringByMode(i);
                int highScore = preferences.getInteger(modeId);
                Gdx.app.log(modeId, String.valueOf(highScore));
                Games.Leaderboards.submitScore(gameHelper.getApiClient(),
                        modeId, highScore);
            }

        }
    }


    private String getStringByMode(int mode) {
        switch (mode) {
            case Constants.EASY_MODE:
                return getString(R.string.leaderboard_easy_highscore);
            case Constants.MEDIUM_MODE:
                return getString(R.string.leaderboard_medium_highscore);
            case Constants.HARD_MODE:
                return getString(R.string.leaderboard_hard_highscore);
            case Constants.INSANE_MODE:
                return getString(R.string.leaderboard_insane_highscore);
            default:
                return getString(R.string.leaderboard_easy_highscore);
        }
    }

    @Override
    public void showAchievement() {
        if (isSignedIn() == true) {
            startActivityForResult(Games.Achievements.getAchievementsIntent(gameHelper.getApiClient()), requestCode);
        } else {
            signIn();
        }
    }

    @Override
    public void showScore() {
        if (isSignedIn() == true) {
            startActivityForResult(Games.Leaderboards.getAllLeaderboardsIntent(gameHelper.getApiClient()), requestCode);
        } else {
            signIn();
        }
    }

    @Override
    public boolean isSignedIn() {
        return gameHelper.isSignedIn();
    }

    @Override
    public void onRewardedVideoAdLoaded() {
        Log.i(TAG, "onRewardedVideoAdLoaded() called");
    }

    @Override
    public void onRewardedVideoAdOpened() {
        Log.i(TAG, "onRewardedVideoAdOpened() called");
    }

    @Override
    public void onRewardedVideoStarted() {
        Log.i(TAG, "onRewardedVideoStarted() called");
    }

    @Override
    public void onRewardedVideoAdClosed() {
        Log.i(TAG, "onRewardedVideoAdClosed() called");
    }

    @Override
    public void onRewarded(RewardItem rewardItem) {
        Log.i(TAG, "onRewarded() called with: rewardItem = [" + rewardItem + "]");
    }

    @Override
    public void onRewardedVideoAdLeftApplication() {
        Log.i(TAG, "onRewardedVideoAdLeftApplication() called");
    }

    @Override
    public void onRewardedVideoAdFailedToLoad(int i) {
        Log.i(TAG, "onRewardedVideoAdFailedToLoad() called with: i = [" + i + "]");
    }

    private void loadRewardedVideoAd() {

        try {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!mRewardedVideoAd.isLoaded()) {
                        AdRequest adRequest = new AdRequest.Builder().build();
                        mRewardedVideoAd.loadAd(AD_UNIT_ID, adRequest);
                    }
                }
            });
        } catch (Exception e) {
            Gdx.app.log("MainActivity", "Ad me error aa gayi  " + e.getMessage() + ".");
        }
    }

    private void showRewardedVideo() {
        try {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mRewardedVideoAd.isLoaded()) {
                        mRewardedVideoAd.show();
                    }
                }
            });
        }
        catch (Exception e){
            Gdx.app.log("MainActivity","Ad Show karne me error" + e.getMessage());
        }

    }
}
