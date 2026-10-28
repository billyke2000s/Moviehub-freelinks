import { readFileSync, writeFileSync } from "node:fs";
import { createPrivateKey, createPublicKey, sign, verify } from "node:crypto";
if (!process.env.PROVIDER_SIGNING_KEY_B64) throw new Error("Provider signing key is not configured");
const key = createPrivateKey(Buffer.from(process.env.PROVIDER_SIGNING_KEY_B64, "base64"));
if (key.asymmetricKeyType !== "rsa" || key.asymmetricKeyDetails.modulusLength < 3072)
    throw new Error("RSA >=3072 required");
const trust = JSON.parse(readFileSync("android-tv-launcher/app/src/main/assets/providers/trust.json"));
const pinned = createPublicKey({ key: Buffer.from(trust.publicKey, "base64"), format: "der", type: "spki" });
const bytes = readFileSync("provider-engine/dist/providers.json");
const signature = sign("sha256", bytes, key);
if (!verify("sha256", bytes, pinned, signature))
    throw new Error("Signing key does not match APK trust anchor");
writeFileSync("provider-engine/dist/providers.sig", signature);
