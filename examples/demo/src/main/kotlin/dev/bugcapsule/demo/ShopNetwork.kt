package dev.bugcapsule.demo

import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class Profile(val name: String?, val tier: String?)
data class Recommendations(val items: List<String>?)
data class CartItemDto(val sku: String, val name: String, val price: Double?, val qty: Int)
data class CartDto(val cartId: String?, val items: List<CartItemDto>?)
data class CheckoutRequest(
    @SerializedName("cart_id") val cartId: String?,
    val amount: Double,
    val timestamp: Long,
    val nonce: Int,
)
data class CheckoutResponse(val orderId: String?, val status: String?)
data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val token: String?, val name: String?)

interface ShopApi {
    @GET("api/profile")
    suspend fun profile(): Profile

    @GET("api/recommendations")
    suspend fun recommendations(): Recommendations

    @GET("api/cart")
    suspend fun cart(): CartDto

    @POST("api/checkout")
    suspend fun checkout(@Body body: CheckoutRequest): CheckoutResponse

    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse
}

/**
 * Retrofit on OkHttp. Interceptor order: the app's own interceptor, then
 * BugCapsule's (innermost application interceptor), then the in-process fake
 * shop server, which plays the role of the network (below the SDK hook, so
 * replay serves recorded responses instead of running it).
 */
object ShopNetwork {
    lateinit var api: ShopApi
        private set
    lateinit var client: OkHttpClient
        private set

    fun init() {
        client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer demo-session-token-123456")
                        .header("X-Client", "bugcapsule-android-demo/${BuildConfig.VERSION_NAME}")
                        .build(),
                )
            })
            .addInterceptor(ShopApp.okHttp.interceptor)
            .addInterceptor(FakeShopServer())
            .build()
        api = Retrofit.Builder()
            .baseUrl("https://api.shop.example/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ShopApi::class.java)
    }
}
