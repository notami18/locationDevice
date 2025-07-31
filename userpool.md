🔐 User Pool vs Identity Pool para Certificados IoT

No hay problema usando User Pool en lugar de Identity Pool para este caso de uso. De hecho,
es una arquitectura válida y común. Aquí te explico:

¿Por qué funciona User Pool?

1. Identificador único: User Pool te da un userId único por usuario autenticado
2. Certificados 1:1: Cada usuario (dispositivo) tendrá su propio certificado IoT
3. Seguridad: Autenticación robusta con credenciales

Comparación:

| Aspecto       | Identity Pool | User Pool (tu caso)     |
| ------------- | ------------- | ----------------------- |
| ID único      | identityId    | userId ✅               |
| Autenticación | Roles IAM     | Credenciales usuario ✅ |
| Complejidad   | Mayor         | Menor ✅                |
| Caso de uso   | Apps públicas | Apps con login ✅       |

Recomendaciones:

✅ MANTÉN User Pool si:

- Tus dispositivos P10 requieren autenticación con credenciales
- Quieres control granular de usuarios
- Necesitas gestión de permisos por usuario

🔄 Considera Identity Pool si:

- Los dispositivos son "anónimos"
- Necesitas acceso temporal/guest
- Quieres roles IAM automáticos

Para crear Identity Pool (si decides cambiar):
