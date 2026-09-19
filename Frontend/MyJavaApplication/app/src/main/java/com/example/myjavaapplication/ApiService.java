package com.example.myjavaapplication;

import java.util.List;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.PUT;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface ApiService {

    @POST("api/login")
    Call<AuthResponse> loginUser(@Body LoginRequest loginRequest);

    @POST("api/signup")
    Call<ResponseBody> signupUser(@Body SignupRequest signupRequest);

    // Typed to UsageSyncResponse rather than Void so a sync that matched no
    // subscriptions is distinguishable from one that stored data.
    @PUT("usage")
    Call<UsageSyncResponse> syncUsage(@Header("Authorization") String token, @Body UsageRequest request);

    /**
     * The signed-in user's subscriptions. Scoped by the token, so no user id is sent.
     *
     * <p>Called before each sync to decide which of the device's apps are worth
     * reporting. The activeOnly filter is deliberately left off: the server accepts
     * usage for a paused or recently cancelled subscription too, and passing
     * activeOnly=true would drop usage it would otherwise have stored.
     */
    @GET("api/subscriptions")
    Call<List<SubscriptionResponse>> getSubscriptions(@Header("Authorization") String token);

    /**
     * Adds a subscription. Returns 201 with the stored row, or 409 if the user already
     * has one for the same package -- the server treats package name as unique per
     * user, because two rows for one package would double-count the same usage.
     */
    @POST("api/subscriptions")
    Call<SubscriptionResponse> createSubscription(
            @Header("Authorization") String token, @Body SubscriptionRequestBody body);

    /**
     * A soft cancel: the server sets status to CANCELLED and keeps the usage snapshots
     * and billing rows, since those are the evidence behind the verdict. Reversible
     * with {@link #updateSubscription} and a status-only body.
     */
    @DELETE("api/subscriptions/{id}")
    Call<SubscriptionResponse> cancelSubscription(
            @Header("Authorization") String token, @Path("id") Long id);

    /**
     * Partial update -- the server skips null fields, so the app sends a body carrying
     * only status. That is all this client changes; price and category still need the
     * API directly.
     */
    @PUT("api/subscriptions/{id}")
    Call<SubscriptionResponse> updateSubscription(
            @Header("Authorization") String token, @Path("id") Long id, @Body SubscriptionRequestBody body);

    @GET("api/dashboard/{userId}")
    Call<List<DashBoardResponseDTO>> getDashboard(@Header("Authorization") String token, @Path("userId") Long userId);

    /**
     * Account-level totals: spend, what a cancel would recover, and how much of the
     * spend is going unused. Separate call from getDashboard because it is a different
     * shape, not a different slice of the same rows.
     */
    @GET("api/dashboard/{userId}/summary")
    Call<DashboardSummaryResponse> getDashboardSummary(
            @Header("Authorization") String token, @Path("userId") Long userId);
}
