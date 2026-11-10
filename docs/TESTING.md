# Pengujian manual

[Kembali ke README](../README.md)

Build, unit test JVM, dan lint bukan bukti hasil render atau perilaku Android. Daftar ini adalah **rencana pengujian**, bukan klaim semua langkah telah lulus. Penerimaan manual UI terkunci terbaru masih pending. Gunakan perangkat nonkritis milik sendiri; jangan melakukan panggilan darurat nyata.

## Persiapan aman

- Catat versi Android, versi aplikasi, status izin, dan langkah reproduksi tanpa nomor seri, identitas akun, atau data pribadi.
- Periksa jalur pemulihan Setelan Android sebelum mengaktifkan layanan. Pilih aplikasi penting saat mode normal, dan gunakan task dummy.
- Pasang sebagai update hanya dengan application ID dan sertifikat yang sama serta version code kompatibel. Jangan uninstall/hapus data untuk mengatasi kegagalan update jika data masih diperlukan.
- Jika membagikan screenshot, gunakan data dummy, sembunyikan notifikasi/status pribadi, dan tinjau seluruh gambar. Jangan membagikan log mentah.

## Checklist perilaku

- [ ] Normal: tambah, ubah, hapus; picker tanggal/jam dan input keyboard berfungsi; jam mulai wajib, lead opsional.
- [ ] Sekali, harian, hari mingguan pilihan, dan bulanan bekerja sesuai zona waktu; tanggal bulanan yang tidak ada dilewati.
- [ ] Lead yang melewati tengah malam memicu waktu kunci yang benar. Task lewat waktu tetap tertunda setelah tengah malam.
- [ ] Saat editor/picker/keyboard terbuka lalu task jatuh tempo, permukaan terkunci muncul tanpa editor, navigasi normal, atau picker yang masih dapat dipakai.
- [ ] Back, rotasi, pindah aplikasi, serta buka ulang saat tertunda tidak membuka editor atau perubahan task/daftar aplikasi.
- [ ] Daftar aplikasi saat terkunci hanya-baca. Aplikasi pilihan yang masih terpasang dapat dibuka; pilihan yang hilang tidak ditampilkan sebagai berhasil diluncurkan.
- [ ] Tombol telepon dapat membuka dialer tanpa membuat panggilan; pastikan akses sistem normal tetap tersedia. Perilaku lockscreen diperiksa terpisah, tidak diasumsikan.
- [ ] Belum, Back pada dialog, dan dismiss tidak menghapus kewajiban tertunda.
- [ ] Dua task tertunda: konfirmasi pertama tetap terkunci; konfirmasi terakhir kembali normal. Jadwal berulang tidak terhapus.
- [ ] Setelah semua selesai, CRUD, pengaturan, picker, dan keyboard kembali berfungsi.
- [ ] Cabut izin Aksesibilitas atau putuskan layanan: status tidak mengklaim pembatasan aktif. Tanpa izin alarm tepat, tampilkan fallback tidak tepat.
- [ ] Uji layar mati, Doze, reboot lalu unlock, perubahan jam/zona waktu, dan force-stop lalu buka ulang. Catat keterlambatan dan batasan perangkat.
- [ ] Update tanpa uninstall/hapus data: task, penyelesaian, dan pilihan aplikasi tetap ada. Jika penyimpanan gagal dibaca, jangan hapus data.

## Melaporkan hasil

Sertakan hasil aktual per langkah, hasil yang diharapkan, versi build/Android, dan batasan pengujian. Bedakan test JVM, lint, pengujian render, serta pengujian perangkat. Jangan menyimpulkan akses darurat universal atau keandalan produksi dari satu perangkat.
