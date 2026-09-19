package com.example.myjavaapplication;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;

public class RetrofitClient {
    private static Retrofit retrofit = null;

    /**
     * Injected at build time rather than hardcoding a former DHCP address. The default
     * is http://10.0.2.2:8081/ for an Android emulator; local.properties can override
     * backendBaseUrl for a physical device. HTTP is development-only: use HTTPS for a
     * deployed build because JWTs must never cross an untrusted network in the clear.
     */
    private static final String BASE_URL = BuildConfig.BACKEND_BASE_URL;

    public static Retrofit getClient() {
        if (retrofit == null) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            // BASIC, not BODY. At BODY this interceptor wrote the login request body --
            // the password in plaintext -- and the Authorization header carrying the JWT
            // into Logcat on every call, in every build type. BASIC still shows method,
            // URL, status code and timing, which is what makes a 401 diagnosable.
            // Raise it to BODY temporarily when you need to inspect a payload.
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
            // Belt and braces: if the level is raised, the token is still not printed.
            logging.redactHeader("Authorization");

            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();

            retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit;
    }
}
