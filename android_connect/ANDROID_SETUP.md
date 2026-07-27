# Android Studio connection setup

## 1. Add dependencies

Add these to your app module `build.gradle` or `build.gradle.kts`.

Groovy:

```gradle
dependencies {
    implementation "com.squareup.retrofit2:retrofit:2.11.0"
    implementation "com.squareup.retrofit2:converter-gson:2.11.0"
    implementation "com.squareup.okhttp3:okhttp:4.12.0"
}
```

Kotlin DSL:

```kotlin
dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

## 2. Add internet permission

Add this to `AndroidManifest.xml`.

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

If you use `http://` instead of `https://`, also add this to the `<application>` tag during local testing:

```xml
<application
    android:usesCleartextTraffic="true">
</application>
```

## 3. Base URL

Android emulator calling a FastAPI server on the same PC:

```kotlin
val client = ShoppingApiClient("http://10.0.2.2:8000/")
```

Real Android phone on the same Wi-Fi:

```kotlin
val client = ShoppingApiClient("http://YOUR_PC_IP:8000/")
```

Find your PC IP in PowerShell:

```powershell
ipconfig
```

Use the IPv4 address, for example:

```kotlin
val client = ShoppingApiClient("http://192.168.0.15:8000/")
```

## 4. Example call from a coroutine

```kotlin
val client = ShoppingApiClient("http://10.0.2.2:8000/")
val result = client.analyzeImage(context, imageUri)

productNameTextView.text = result.product_name ?: result.message ?: "분석 결과 없음"
brandTextView.text = result.brand ?: "-"
priceTextView.text = result.prices?.firstOrNull()?.price ?: "-"
```
