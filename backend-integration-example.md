# 🔗 **Integración Backend para Certificados Dinámicos**

## 📋 **GraphQL Schema (AppSync)**

```graphql
# Mutation para generar certificado único por dispositivo
type Mutation {
    generateDeviceCertificate(input: GenerateDeviceCertificateInput!): DeviceCertificateResponse!
}

input GenerateDeviceCertificateInput {
    deviceId: String!
    cognitoIdentityId: String!
}

type DeviceCertificateResponse {
    success: Boolean!
    deviceId: String!
    thingName: String!
    certificateArn: String
    certificatePem: String
    privateKey: String
    message: String
}

# Query para verificar estado del certificado
type Query {
    getDeviceCertificateStatus(deviceId: String!): DeviceCertificateStatus!
}

type DeviceCertificateStatus {
    deviceId: String!
    thingName: String!
    certificateExists: Boolean!
    isActive: Boolean!
    createdAt: String
    lastConnected: String
}
```

## ⚡ **Lambda Resolver (Node.js)**

```javascript
const AWS = require('aws-sdk');
const iot = new AWS.Iot({ region: process.env.AWS_REGION });

exports.handler = async (event) => {
    console.log('🔐 Generando certificado para dispositivo:', JSON.stringify(event));
    
    const { deviceId, cognitoIdentityId } = event.arguments.input;
    const thingName = `device-${cognitoIdentityId}`;
    
    try {
        // 1. Verificar si el Thing ya existe
        let thingExists = false;
        try {
            await iot.describeThing({ thingName }).promise();
            thingExists = true;
        } catch (e) {
            if (e.code !== 'ResourceNotFoundException') {
                throw e;
            }
        }
        
        // 2. Crear Thing si no existe
        if (!thingExists) {
            await iot.createThing({
                thingName,
                attributePayload: {
                    attributes: {
                        deviceId,
                        cognitoIdentityId,
                        createdAt: new Date().toISOString()
                    }
                }
            }).promise();
            
            console.log(`✅ Thing creado: ${thingName}`);
        }
        
        // 3. Generar certificado
        const createCertResponse = await iot.createKeysAndCertificate({
            setAsActive: true
        }).promise();
        
        // 4. Crear política específica para el dispositivo
        const policyName = `DevicePolicy-${cognitoIdentityId}`;
        const policyDocument = {
            Version: "2012-10-17",
            Statement: [
                {
                    Effect: "Allow",
                    Action: [
                        "iot:Connect"
                    ],
                    Resource: `arn:aws:iot:${process.env.AWS_REGION}:${process.env.AWS_ACCOUNT_ID}:client/${thingName}`
                },
                {
                    Effect: "Allow",
                    Action: [
                        "iot:Publish"
                    ],
                    Resource: `arn:aws:iot:${process.env.AWS_REGION}:${process.env.AWS_ACCOUNT_ID}:topic/devices/location`
                }
            ]
        };
        
        // Crear política si no existe
        try {
            await iot.createPolicy({
                policyName,
                policyDocument: JSON.stringify(policyDocument)
            }).promise();
        } catch (e) {
            if (e.code !== 'ResourceAlreadyExistsException') {
                throw e;
            }
        }
        
        // 5. Adjuntar política al certificado
        await iot.attachPolicy({
            policyName,
            target: createCertResponse.certificateArn
        }).promise();
        
        // 6. Adjuntar certificado al Thing
        await iot.attachThingPrincipal({
            thingName,
            principal: createCertResponse.certificateArn
        }).promise();
        
        console.log(`✅ Certificado generado y configurado para ${thingName}`);
        
        return {
            success: true,
            deviceId,
            thingName,
            certificateArn: createCertResponse.certificateArn,
            certificatePem: createCertResponse.certificatePem,
            privateKey: createCertResponse.keyPair.PrivateKey,
            message: "Certificado generado exitosamente"
        };
        
    } catch (error) {
        console.error('❌ Error generando certificado:', error);
        
        return {
            success: false,
            deviceId,
            thingName,
            certificateArn: null,
            certificatePem: null,
            privateKey: null,
            message: `Error: ${error.message}`
        };
    }
};
```

## 📱 **Integración Android con Amplify**

```kotlin
// En CertificateManager.kt - Método real para solicitar certificados
private suspend fun requestCertificateFromBackend(deviceId: String): CertificateData? {
    return withContext(Dispatchers.IO) {
        try {
            // Obtener Cognito Identity ID
            val cognitoIdentityId = Amplify.Auth.getCurrentUser().userId
            
            // Crear input para la mutation
            val input = GenerateDeviceCertificateInput.builder()
                .deviceId(deviceId)
                .cognitoIdentityId(cognitoIdentityId)
                .build()
            
            // Ejecutar mutation
            val mutation = GenerateDeviceCertificateMutation.builder()
                .input(input)
                .build()
            
            val response = Amplify.API.mutate(mutation).await()
            
            if (response.data.success) {
                Log.d(TAG, "✅ Certificado generado por backend: ${response.data.thingName}")
                
                CertificateData(
                    clientCertificate = response.data.certificatePem,
                    privateKey = response.data.privateKey
                )
            } else {
                Log.e(TAG, "❌ Error del backend: ${response.data.message}")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error llamando al backend: ${e.message}")
            null
        }
    }
}
```

## 🏗️ **Configuración de Amplify CLI**

```bash
# 1. Inicializar Amplify
amplify init

# 2. Agregar API GraphQL
amplify add api
# Seleccionar: GraphQL
# Seleccionar: Amazon Cognito User Pool

# 3. Agregar función Lambda
amplify add function
# Nombre: generateDeviceCertificate

# 4. Configurar permisos IAM para Lambda
# En amplify/backend/function/generateDeviceCertificate/custom-policies.json
```

```json
[
    {
        "Action": [
            "iot:CreateThing",
            "iot:DescribeThing",
            "iot:CreateKeysAndCertificate",
            "iot:CreatePolicy",
            "iot:AttachPolicy",
            "iot:AttachThingPrincipal"
        ],
        "Resource": "*"
    }
]
```

## 🔄 **Flujo Completo de Implementación**

1. **Primera ejecución del dispositivo:**
   - Device se autentica con Cognito
   - CertificateManager verifica certificados locales
   - No encuentra certificados → Llama a AppSync
   - Lambda genera Thing + Certificado único
   - Device descarga y almacena certificados
   - Conecta con certificado único

2. **Ejecuciones posteriores:**
   - Device carga certificados desde storage local
   - Conecta directamente con certificado único
   - Sin conflictos con otros dispositivos

## ⚠️ **Consideraciones de Seguridad**

- **Rotación de certificados:** Implementar TTL y renovación automática
- **Revocación granular:** Poder desactivar dispositivo específico
- **Almacenamiento seguro:** Usar Android Keystore para claves privadas
- **Validación de identidad:** Verificar Cognito identity antes de generar certificado

## 📊 **Monitoreo y Métricas**

```javascript
// CloudWatch métricas personalizadas
const cloudwatch = new AWS.CloudWatch();

await cloudwatch.putMetricData({
    Namespace: 'IoT/DeviceCertificates',
    MetricData: [
        {
            MetricName: 'CertificatesGenerated',
            Value: 1,
            Unit: 'Count',
            Dimensions: [
                {
                    Name: 'DeviceType',
                    Value: 'P10'
                }
            ]
        }
    ]
}).promise();
```

## 🧪 **Testing y Validación**

### **Pruebas de Certificados Dinámicos:**
```bash
# Logs esperados en Android Studio
🔐 Inicializando certificados dinámicos...
📱 Dispositivo ID: user-12345-abcd
🏷️ Thing Name: device-user-12345-abcd
✅ Certificados dinámicos cargados para dispositivo: user-12345-abcd
✅ Conectado exitosamente a AWS IoT
```

### **Pruebas de Almacenamiento Robusto:**
```bash
# Con MQTT conectado
📤 MQTT disponible, enviando inmediatamente
✅ Enviado a AWS IoT (Cola: 0)

# Sin MQTT disponible  
📥 MQTT no disponible, ubicación guardada en sistema robusto
📥 Datos en cola local: 15
🔄 Procesando 15 items pendientes...
```

### **Comandos de Verificación AWS:**
```bash
# Verificar Thing creado
aws iot describe-thing --thing-name device-user-12345-abcd

# Verificar certificado activo
aws iot list-thing-principals --thing-name device-user-12345-abcd

# Verificar métricas de conectividad
aws logs filter-log-events --log-group-name /aws/lambda/generateDeviceCertificate
```

## 🔍 **Troubleshooting**

### **Problema: Certificados no se descargan**
```kotlin
// Verificar en logs:
❌ No se pudo obtener usuario Cognito
// Solución: Verificar configuración Amplify

❌ Error del backend: Access denied
// Solución: Verificar permisos IAM Lambda
```

### **Problema: Datos no se sincronizan**
```kotlin
// Verificar storage stats:
val stats = localStorageManager.getStorageStats()
Log.d("DEBUG", "Pendientes: ${stats.pendingCount}")
// Si >0 y MQTT conectado, hay problema en processBatch()
```

Esta implementación garantiza:
- ✅ **Un certificado por dispositivo**
- ✅ **Trazabilidad completa** 
- ✅ **Integración con Cognito**
- ✅ **Escalabilidad automática**
- ✅ **Seguridad mejorada**
- ✅ **Cero pérdida de datos**
- ✅ **Recuperación automática**