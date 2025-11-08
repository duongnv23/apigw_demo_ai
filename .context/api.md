### FCBV API VRB — Summary Specification

Generated: 2025-11-08 14:50
Source: data/FCBV_API_VRB.postman_collection.json

---

#### Environments / Variables
- PRODUCT_URL: https://api.fcbv.vn
- UAT_URL: https://test-api.fcbv.vn
- TOKEN: set after successful Login (collection test script assigns `pm.response.json().token` to `{{TOKEN}}`).

Use `{{UAT_URL}}` or `{{PRODUCT_URL}}` as the base URL in examples below.

---

### Authentication
- Login returns a JSON body containing `token`.
- Subsequent protected endpoints use Bearer Auth with header: `Authorization: Bearer {{TOKEN}}`.

---

### Endpoints

1) POST /login
- URL: `{{UAT_URL}}/login`
- Auth: none
- Body (form-data):
  - user: string (KCI username)
  - pass: string (KCI password)
- On success: response JSON includes `token`. The collection test saves it to `{{TOKEN}}`.
- Example (curl):
```
curl -X POST "{{UAT_URL}}/login" \
  -F "user=fcbvKCI" \
  -F "pass=Fcbv@2025"
```

2) POST /otp (Get OTP)
- URL: `{{UAT_URL}}/otp`
- Auth: none
- Body (form-data):
  - user: string (Username)
  - pass: string (Password)
- Example:
```
curl -X POST "{{UAT_URL}}/otp" \
  -F "user=fcbvT034" \
  -F "pass=Fcbv@2025"
```

3) POST /change_pass (Change Password)
- URL: `{{UAT_URL}}/change_pass`
- Auth: none
- Body (form-data):
  - user: string (Username)
  - old_pass: string
  - new_pass: string
  - otp: string (OTP from Authen)
- Example:
```
curl -X POST "{{UAT_URL}}/change_pass" \
  -F "user=fcbvT034" \
  -F "old_pass=Fcbv@2025" \
  -F "new_pass=Fcbv@2025" \
  -F "otp=123456"
```

4) POST /rireq (Risk/Request — multiple subject types)
- URL: `{{UAT_URL}}/rireq`
- Auth: Bearer `{{TOKEN}}`
- Body: JSON. Three sample variants exist in the collection:
  - Subject.Person (credit/instalment contract)
  - Subject.Company (credit card contract)
  - Subject.IndividualConcern (sole proprietor/owner data)
- Example minimal structure (Person):
```
{
  "Contract": {
    "DateRequestContract": "DDMMYYYY",
    "OperationType": "..",
    "CodCurrency": "VND",
    "Instalment": {
      "AmountFinancedCapital": "...",
      "NumTotalInstalment": "...",
      "CodPaymentPeriodicity": "M"
    }
  },
  "Subject": {
    "Person": {
      "Name": "...",
      "Gender": "M|F",
      "DateOfBirth": "DDMMYYYY",
      "PlaceOfBirth": "...",
      "CountryOfBirth": "VN",
      "IDCard": "...",
      "IDCard2": "",
      "TIN": "",
      "Address": {
        "Main": { "FullAddress": "..." },
        "Additional": { "FullAddress": "" }
      },
      "Document": {
        "Type": "",
        "Number": "",
        "DateIssued": "",
        "CountryIssued": "VN"
      },
      "Reference": { "Number": "..." }
    }
  },
  "Role": "A"
}
```
- Headers: `Content-Type: application/json`

5) POST /cireq (Customer Info Request)
- URL: `{{UAT_URL}}/cireq`
- Auth: Bearer `{{TOKEN}}`
- Body (JSON):
```
{
  "Subject": {
    "CBSubjectCode": "...",
    "FISubjectCode": ""
  }
}
```
- Headers: `Content-Type: application/json`

6) POST /prreq (Report/PR Request)
- URL: `{{UAT_URL}}/prreq`
- Auth: Bearer `{{TOKEN}}`
- Body (JSON) — includes a `Subject.Person` and `Report.Type`:
```
{
  "Subject": {
    "Person": {
      "Name": "...",
      "Gender": "M|F",
      "DateOfBirth": "DDMMYYYY",
      "PlaceOfBirth": "...",
      "CountryOfBirth": "VN",
      "IDCard": "...",
      "IDCard2": "",
      "TIN": "",
      "Address": {
        "Main": { "FullAddress": "..." },
        "Additional": { "FullAddress": "" }
      },
      "Document": {
        "Type": "",
        "Number": "",
        "DateIssued": "",
        "CountryIssued": "VN"
      },
      "Reference": { "Number": "..." }
    }
  },
  "Report": { "Type": "1" }
}
```
- Headers: `Content-Type: application/json`

7) POST /cureq (Contract Update Request)
- URL: `{{UAT_URL}}/cureq`
- Auth: Bearer `{{TOKEN}}`
- Body (JSON):
```
{
  "Contract": {
    "CBContractCode": "...",
    "FIContractCode": ""
  },
  "ContractData": {
    "ContractPhase": "RQ",
    "AmountFinancedCapital": "0",
    "AmountMonthlyInstalment": "0",
    "NumTotalInstalment": "0",
    "CodPaymentPeriodicity": "M"
  }
}
```
- Headers: `Content-Type: application/json`

8) POST /ecreq (Existing Contract / Customer Add?)
- URL: `{{UAT_URL}}/ecreq`
- Auth: Bearer `{{TOKEN}}`
- Body (JSON): combines contract code and `Subject.Person`, with `Role`:
```
{
  "Contract": {
    "CBContractCode": "...",
    "FIContractCode": ""
  },
  "Subject": {
    "Person": {
      "Name": "...",
      "Gender": "M|F",
      "DateOfBirth": "DDMMYYYY",
      "PlaceOfBirth": "...",
      "CountryOfBirth": "VN",
      "IDCard": "...",
      "IDCard2": "",
      "TIN": "",
      "Address": {
        "Main": { "FullAddress": "..." },
        "Additional": { "FullAddress": "" }
      },
      "Document": {
        "Type": "",
        "Number": "",
        "DateIssued": "",
        "CountryIssued": "VN"
      },
      "Reference": { "Number": "" }
    }
  },
  "Role": "C"
}
```
- Headers: `Content-Type: application/json`

---

### General Notes
- Dates in examples use format `DDMMYYYY`.
- Currency codes sample show `VND`.
- Periodicity sample `M` (monthly).
- For JSON endpoints, set `Content-Type: application/json`.
- For form-data endpoints (`/login`, `/otp`, `/change_pass`), send as multipart form-data.
- Use `{{TOKEN}}` from the login step for Bearer auth on protected endpoints.
