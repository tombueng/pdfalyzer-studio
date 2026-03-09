# Cloud Signature Consortium (CSC) API -- Known Implementations

The **Cloud Signature Consortium (CSC)** defines a standardized **remote
digital signing API** used by Trust Service Providers (TSPs). Each
provider operates **its own API endpoint**.

There is **no global registry**, but the ecosystem is mainly composed
of:

-   Qualified Trust Service Providers (QTSPs)
-   Certificate Authorities
-   Remote Signing Services
-   National eID providers
-   Enterprise HSM platforms

------------------------------------------------------------------------

# 1. Known CSC API Providers

  Provider                  Base URL
  ------------------------- -----------------------------------------------
  DigiCert                  https://clientauth.digicert.com/csc/v2/
  GlobalSign                https://emea.api.globalsign.com/csc/v2/
  InfoCert                  https://services.infocert.it/csc/v2/
  Namirial                  https://api.namirial.com/csc/v2/
  Universign                https://api.universign.com/csc/v2/
  SSL.com eSigner           https://api.ssl.com/esigner/csc/v1/
  PrimeSign (Cryptas)       https://`<tenant>`{=html}.cryptas.com/csc/v2/
  ADACOM                    https://sign.adacom.com/csc/v2/
  QuoVadis (DigiCert)       https://services.quovadisglobal.com/csc/v2/
  Buypass                   https://api.buypass.com/csc/v2/
  Intesi Group              https://qes.intesigroup.com/csc/v2/
  certSIGN                  https://services.certsign.ro/csc/v2/
  D-Trust                   https://services.d-trust.net/csc/v2/
  Swisscom Trust Services   https://trustservices.swisscom.com/csc/v2/
  IDnow Trust Services      https://api.idnow.io/csc/v2/
  Evrotrust                 https://api.evrotrust.com/csc/v2/
  TrustPro                  https://api.trustpro.com.tw/csc/v2/
  TrustGate                 https://api.trustgate.com.my/csc/v2/

------------------------------------------------------------------------

# 2. Ascertia / ADSS Based CSC Deployments

Many remote signing infrastructures are built on **Ascertia ADSS
Server**, which exposes the CSC API through the following base path:

https://`<domain>`{=html}/adss/service/ras/csc/v1/

Example deployment:

https://ras-test.jcc.com.cy/adss/service/ras/csc/v1/

Common variations:

-   https://signinghub.ascertia.com/adss/service/ras/csc/v1/
-   https://sign.`<provider>`{=html}.com/adss/service/ras/csc/v1/
-   https://ras.`<provider>`{=html}.com/adss/service/ras/csc/v1/

------------------------------------------------------------------------

# 3. Government and eID Implementations

Some national identity systems provide CSC compatible remote signatures.

  Country       Service
  ------------- ------------------------------------
  Norway        BankID Remote Signing
  Italy         InfoCert / Namirial SPID ecosystem
  Austria       PrimeSign infrastructure
  Spain         FNMT integrations
  Estonia       eID Easy remote signing
  Switzerland   Swisscom Trust Services

These deployments typically require:

-   OAuth2
-   OpenID Connect
-   eID authentication
-   Qualified signature credentials

------------------------------------------------------------------------

# 4. Standard CSC API Structure

Base path pattern:

https://provider-domain/.../csc/v2/

Core endpoints defined by the specification:

  Method   Endpoint                 Purpose
  -------- ------------------------ ------------------------------------
  GET      /info                    Service discovery
  POST     /credentials/list        List available signing credentials
  POST     /credentials/info        Credential details
  POST     /credentials/authorize   User authorization
  POST     /signatures/signHash     Sign precomputed hash
  POST     /signatures/signDoc      Sign full document

Example request:

GET https://api.ssl.com/esigner/csc/v1/info

Example response:

``` json
{
  "specs": "CSC API v2",
  "name": "Remote Signing Service",
  "authType": ["oauth2"]
}
```

------------------------------------------------------------------------

# 5. CSC Endpoint Discovery (OSINT Method)

Because the API standard requires a **public discovery endpoint**,
servers can often be found by scanning for:

-   /csc/v2/info
-   /csc/v1/info
-   /adss/service/ras/csc/v1/info

------------------------------------------------------------------------

# 6. Example CSC Discovery Script

``` python
import requests

paths = [
"/csc/v2/info",
"/csc/v1/info",
"/adss/service/ras/csc/v1/info"
]

hosts = [
"api.ssl.com",
"clientauth.digicert.com",
"api.universign.com",
"services.infocert.it"
]

for h in hosts:
    for p in paths:
        url = f"https://{h}{p}"
        try:
            r = requests.get(url, timeout=5)
            if r.status_code == 200:
                print(url)
        except:
            pass
```

------------------------------------------------------------------------

# 7. Typical CSC Ecosystem Vendors

### Trust Service Providers

-   DigiCert
-   GlobalSign
-   InfoCert
-   Namirial
-   Universign
-   SSL.com
-   Buypass
-   Swisscom
-   D-Trust
-   certSIGN

### Technology Providers

-   Ascertia
-   Cryptas
-   Cryptomathic
-   Intesi Group

------------------------------------------------------------------------

# 8. Typical Base URL Patterns

Common patterns used by providers:

-   https://api.`<provider>`{=html}.com/csc/v2/
-   https://services.`<provider>`{=html}.com/csc/v2/
-   https://sign.`<provider>`{=html}.com/csc/v2/
-   https://`<provider>`{=html}/adss/service/ras/csc/v1/

------------------------------------------------------------------------

# 9. Practical Integration Workflow

Typical CSC signing process:

1.  GET /info
2.  OAuth2 authentication
3.  POST /credentials/list
4.  POST /credentials/info
5.  POST /credentials/authorize
6.  POST /signatures/signHash
