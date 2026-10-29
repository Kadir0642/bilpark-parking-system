/// USER_UPDATED olayından sonra saha uygulamasının ne yapacağı.
enum UserUpdateAction {
  /// Değişen bir şey yok (örn. yalnızca ad): sessiz kal.
  none,

  /// Rol artık memur değil: oturum yerelde kapanır.
  roleChanged,

  /// Saha değişti: yeniden giriş gerekir.
  zoneChanged,

  /// Saha boşaltıldı: çıkış yapılır.
  zoneRemoved,
}

/// [role] ve [zoneId] güncel değerler, [currentZoneId] bu cihazda açık sahadır.
UserUpdateAction decideUserUpdate({required String? role, required int? zoneId, required int? currentZoneId}) {
  if (role != 'OFFICER') return UserUpdateAction.roleChanged;
  if (zoneId == null) return UserUpdateAction.zoneRemoved;
  if (zoneId != currentZoneId) return UserUpdateAction.zoneChanged;
  return UserUpdateAction.none;
}
