# Правила R8/ProGuard для release-сборки.
# Минификация отключена (isMinifyEnabled = false) — правила ниже оставлены
# на случай её включения.

# Если включите обфускацию, запретите переименование обратных вызовов USB-приёмников.
-keepclassmembers class org.kiz.boot.** {
    public void onReceive(android.content.Context, android.content.Intent);
}
