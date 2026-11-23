# NotifHub

Aplikasi Android yang mengumpulkan notifikasi dari **semua aplikasi** di HP (WhatsApp,
Instagram, email, Shopee, bank, dll.) ke satu tempat, lalu memilahnya dengan Gemini menjadi
**Urgent / Penting / Normal / Rendah**.

## Cara kerja
- Notifikasi sistem (musik yang diputar, progres unduhan, service latar belakang) dilewati otomatis. Di Pengaturan bisa dibatasi hanya ke WA, Facebook, Messenger, dan email.
- Membaca notifikasi yang masuk ke HP (izin *Akses notifikasi*), jadi tidak perlu login WA/FB/email di aplikasi ini.
- Notifikasi baru ditampung beberapa detik, lalu dikirim sekaligus (maks. 25) ke Gemini supaya hemat kuota.
- Kalau API key kosong atau Gemini gagal, dipakai aturan kata kunci (ditandai "aturan, bukan AI").
- Tekan lama sebuah notifikasi untuk memindahkan prioritasnya. Koreksimu (15 terakhir) ikut dikirim ke AI sebagai contoh, jadi pemilahannya makin sesuai seleramu.
- Notifikasi URGENT memunculkan peringatan sendiri (bisa dimatikan).
- Ketuk notifikasi untuk membuka chat/email aslinya.

## Cara mendapatkan APK

### Opsi A — GitHub Actions (tanpa install apa pun)
1. Buat repo baru di GitHub, upload seluruh isi folder ini.
2. Buka tab **Actions** → workflow **Build APK** akan berjalan otomatis (~5 menit).
3. Setelah selesai, buka run tersebut → bagian **Artifacts** → unduh `NotifHub-debug-apk`.
4. Ekstrak zip-nya, pindahkan `app-debug.apk` ke HP, lalu install (izinkan "install dari sumber tidak dikenal").

### Opsi B — Android Studio
1. Install Android Studio, pilih **Open** → folder `NotifHub`.
2. Tunggu Gradle sync selesai (terima tawaran upgrade kalau muncul).
3. Colokkan HP (USB debugging aktif) lalu tekan **Run**, atau **Build → Build APK(s)**.

## Setelah terpasang
1. Buka NotifHub → ketuk banner oranye → aktifkan **NotifHub** di daftar akses notifikasi.
2. Menu ⋮ → **Pengaturan**: tempel Gemini API key (dari Google AI Studio), isi "Tentang aku" dan kontak VIP, lalu **Tes koneksi Gemini**.
3. Model default `gemini-3.5-flash-lite`. Kalau suatu saat model ini dihentikan Google, ganti namanya di Pengaturan.

## Batasan yang perlu diketahui
- Hanya menangkap notifikasi yang **muncul sejak** izin diaktifkan; notifikasi lama dan chat yang dibisukan (tanpa notifikasi) tidak ikut.
- Beberapa merek HP (Xiaomi, Oppo, Vivo, Samsung) mematikan service di latar belakang. Kalau notifikasi berhenti masuk, set NotifHub ke **Baterai: Tanpa batasan** / izinkan *autostart*.
- Isi notifikasi, termasuk OTP, dikirim ke Google untuk dipilah. Kalau keberatan, kosongkan API key (mode aturan saja).
- API key disimpan di penyimpanan aplikasi biasa (tidak terenkripsi).
- Data disimpan 30 hari di HP, lalu dihapus otomatis.
