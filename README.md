# NovaTV

Uydu alıcısı gibi kullanılan, açık kaynak (MIT) bir IPTV oynatıcı. İki sürümü var:

| Klasör | Platform | Teknoloji |
|---|---|---|
| [`NovaTV/`](NovaTV/README.md) | Android TV 5.0+ (7.1.1 ve sonrası için ayarlandı) | Kotlin, Media3 ExoPlayer |
| [`NovaTV-webOS/`](NovaTV-webOS/README.md) | LG webOS 1.x (2014) ve sonrası | ES5 / HTML5, isteğe bağlı Python proxy |

## İndir

Hazır paketler **[Releases](https://github.com/JustSadY/NovaTV/releases/latest)** sayfasında:
- `NovaTV-<sürüm>-release.apk`: Android TV
- `com.novatv.webos_<sürüm>_all.ipk`: LG webOS
- `apps.json`: LG için webOS Homebrew Channel deposu

Kurulum adımları her klasörün README'sinde.

## Öne çıkanlar

- **Uydu alıcısı gibi kumanda kullanımı:**
  - CH+/CH− ve ▲/▼ ile kanal değiştirme, 0–9 ile numara girme, ◀ kanal listesi, ▶ hızlı menü.
  - Kanal tuşu çalışmayan kumandalar için Android'de "tuş öğret" seçeneği.
- **Kaynaklar:**
  - M3U/M3U8 adres ve dosya kaynakları; birden çok liste, birleşik numaralı kanal listesi.
  - Favoriler, kategoriler, arama, XMLTV EPG.
- **Telefondan yönetim:** TV'deki QR kodu okutun.
  - Kaynak ve kanal ekleyip silin, kanalları gizleyip yeniden adlandırın, numaraları ve favorileri düzenleyin.
  - İki TV arasında listeleri eşitleyin.
  - LG'de bu iş için yerel ağdaki NovaTV proxy'si gerekir.
- **Güncellemeler:**
  - Android TV, bu deponun Releases sayfasından yeni sürümü kendisi denetler ve indirir; kurulumu kumandayla siz onaylarsınız.
  - LG için Homebrew Channel'a şu depo adresi eklenebilir: `https://github.com/JustSadY/NovaTV/releases/latest/download/apps.json`. Homebrew Channel'ın TV'de kurulu olması gerekir; webOS 1.x'te doğrulanmadı.

## İçerik

NovaTV yalnızca kamu yayıncılarının (TRT, TBMM TV) kendi sunucularındaki resmi ve ücretsiz yayınlarını örnek liste olarak içerir. Ücretli veya izinsiz yayın bağlantısı içermez ve DRM korumalarını aşmaz.

## Lisans

[MIT](LICENSE)
