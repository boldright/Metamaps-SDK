# Metamaps Positioning Engine License

Version 1.0

Copyright 2026 Boldright, Inc. All rights reserved.

This license is an agreement between you and Boldright, Inc. ("Boldright") for the prebuilt positioning engine
provided with the Metamaps SDK. The Engine is part of the Metamaps Services: you may use it only while a Service
Agreement is in effect, and this license ends when that Service Agreement ends. By using, copying, or distributing
the Engine, you agree to this license. If you do not agree, do not use the Engine. If you accept this license on
behalf of a company or other organization, you represent that you have the authority to bind it, and "you" means
that organization.

## 1. Definitions

- **"Engine"** means the files listed in section 2, in every version Boldright provides under this license.
- **"SDK"** means the Metamaps SDK source code in this repository, which is licensed under the Apache License 2.0 in
  [`LICENSE`](LICENSE).
- **"Metamaps Services"** means the online services Boldright operates for Metamaps, including published maps,
  positioning manifests, and the related APIs.
- **"Service Terms"** means the Metamaps Terms of Service at <https://metamaps.jp/policies/terms/>, as amended
  from time to time.
- **"Service Agreement"** means an agreement for the Metamaps Services under the Service Terms, including one
  concluded through a reseller that Boldright designates, and any separate written agreement with Boldright for
  the Metamaps Services.
- **"Your App"** means an application that includes the SDK and the Engine and uses the Metamaps Services under a
  Service Agreement held by you or by the person for whom you develop the application.
- **"End User"** means a person who uses Your App.

## 2. Covered files

This license applies to these prebuilt binaries:

- `ios/Artifacts/MetamapPositioningCore.xcframework`
- `android/maven-repository/jp/metamaps/positioning/metamap-positioning-core/`
- The copies of the binaries above bundled in `flutter/metamap_positioning/` and in Metamaps SDK release archives

The Apache License 2.0 covers the SDK source code. It does not cover the Engine.

## 3. License grant

Subject to this license, Boldright grants you a non-exclusive, non-transferable, non-sublicensable, royalty-free,
worldwide license to:

1. use the Engine to develop, build, test, and run Your App;
2. reproduce the Engine and distribute it to End Users in unmodified binary form, only as part of Your App; and
3. permit End Users to use the Engine as part of Your App.

You may use the Engine only together with the SDK and the Metamaps Services.

## 4. Restrictions

You must not, and must not permit any third party to:

1. distribute the Engine separately from Your App, or as part of a library, SDK, or other developer tool. You may,
   however, keep unmodified copies of this repository (for example, a fork or mirror) and include the Engine in
   Your App's source code repository as part of the SDK, provided that this license and the `NOTICE` file are kept
   with it;
2. modify, translate, or create derivative works of the Engine;
3. reverse engineer, decompile, or disassemble the Engine, or attempt to derive its source code, algorithms, or
   parameters, except to the extent that applicable law expressly permits this despite this restriction;
4. use the Engine with any service other than the Metamaps Services, or to provide positioning outside Your App;
5. use the Engine, or any information obtained from it, to develop or provide a product or service that competes
   with the Engine or the Metamaps Services;
6. remove, obscure, or alter any copyright, license, or other proprietary notice in the Engine; or
7. use the Engine in violation of applicable law, including privacy, data protection, and export control laws.

## 5. Metamaps Services

This license does not grant access to any map or to the Metamaps Services. Your use of the Metamaps Services is
governed by the Service Agreement and the Service Terms. If the Metamaps Services are changed, suspended, or ended
under the Service Terms, the Engine may stop providing positions.

## 6. Your App and End Users

You are responsible for Your App, including its terms of use, its privacy disclosures, and obtaining any permission
and consent that End Users must give for the use of location, Bluetooth, and motion data. The Engine computes
positions on the End User's device and does not itself send data to Boldright.

## 7. Ownership

The Engine is licensed, not sold. Boldright and its licensors retain all right, title, and interest in the Engine,
including all intellectual property rights. All rights not expressly granted in this license are reserved.

## 8. Feedback

If you give Boldright suggestions or feedback about the Engine, Boldright may use them without any obligation to
you.

## 9. New versions

Boldright may release new versions of the Engine under different terms. Each version takes effect when Boldright
publishes it and is governed by the `LICENSE-ENGINE.md` file distributed with it.

## 10. Term and termination

This license remains in effect while the Service Agreement for Your App is in effect, and ends automatically when
that Service Agreement ends for any reason. This license also ends automatically if you breach section 4, or if you
breach any other provision and do not cure the breach within 30 days after Boldright notifies you. When this
license ends, you must stop using the Engine and stop distributing Your App with the Engine. Sections 4, 7, and 11
through 15 survive the end of this license.

## 11. Disclaimer of warranties

TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, THE ENGINE IS PROVIDED "AS IS" AND "AS AVAILABLE", WITHOUT
WARRANTY OF ANY KIND, WHETHER EXPRESS, IMPLIED, OR STATUTORY, INCLUDING ANY WARRANTY OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE, ACCURACY, TITLE, OR NON-INFRINGEMENT.

Positions computed by the Engine are estimates. They can be inaccurate, delayed, or unavailable, for example
because of beacon placement, radio interference, device differences, or the End User's settings. The Engine is not
designed for, and must not be relied on for, emergency response, evacuation guidance, locating people in danger,
or any other use in which its failure could lead to death, personal injury, or severe property damage.

## 12. Limitation of liability

TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, BOLDRIGHT WILL NOT BE LIABLE FOR ANY DAMAGES OF ANY KIND, WHETHER
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE, OR FOR ANY LOSS OF PROFITS, REVENUE, DATA, OR
GOODWILL, ARISING OUT OF OR RELATING TO THE ENGINE OR THIS LICENSE, INCLUDING DAMAGES CAUSED BY DEFECTS IN THE
ENGINE OR BY INACCURATE OR UNAVAILABLE POSITIONS, EVEN IF BOLDRIGHT HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGES.

## 13. Export control

You must comply with all applicable export control and sanctions laws and regulations, including the Foreign
Exchange and Foreign Trade Act of Japan and, where applicable, the export control laws of the United States, when
you use or distribute the Engine or Your App.

## 14. Governing law and jurisdiction

This license is governed by the laws of Japan, without regard to its conflict of laws rules. The United Nations
Convention on Contracts for the International Sale of Goods does not apply. The Tokyo District Court has exclusive
jurisdiction as the court of first instance over any dispute arising out of or relating to this license.

## 15. General

1. This license, together with the Service Agreement and the Service Terms, is the entire agreement between you and
   Boldright regarding the Engine. For matters regarding the Engine, this license prevails over the Service Terms
   if they conflict. If you have a separate written agreement with Boldright that covers the Engine, that agreement
   prevails over this license.
2. If any provision of this license is held invalid or unenforceable, the remaining provisions remain in effect.
3. A failure or delay by Boldright in enforcing any provision is not a waiver of it.
4. You may not assign or transfer this license without Boldright's prior written consent.
5. If this license is translated, the English version prevails.

## 16. Contact

For questions about this license, contact Boldright at <info@boldright.co.jp>.
