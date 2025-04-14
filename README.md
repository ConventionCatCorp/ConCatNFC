# ConCatNFC

This repo implements NFC support for ConCat

## Repo layout

The following directories make up the root of this repo

### ConcatNFCRegProxy

This is the software component that is designed to run on devices where registration staff is printing badges, and where
NFC tags will be programmed. This will be done with an attached USB NFC interface. ConCat registration is driven entirely from a web browser, so this software runs
on these same machines and listens on localhost to accept requests from the local browser that ConCat initiates via
Javascript. The details on how this software works and the APIs is exposed is documented in the README.md of this
directory. There are implementations for ChromeOS, MacOS, Windows and Linux.

### ConcatNFCValidator

This is the software designed to run on a mobile phone with a built-in NFC interface. It will be able to validate
attendee's NFC badges to check they are valid. Right now, there is only an implementation for Android.

## Theory of operation

ConCat NFC tags use NXP NTAG 21x tags. The datasheet for these tags can be found here: [NTAG213/215/216](https://www.nxp.com/docs/en/data-sheet/NTAG213_215_216.pdf)

Genuine or clone tags are supported, so long as the tag implements at least these commands (on
top of standard ISO/IEC 144443 Type A commands):

- GET_VERSION (0x60)
- PWD_AUTH (0x1b)

When Concat first programs one of these tags, it first checks the UID of the tag is not already used in the system,
and generates a random 32 bit value for this attendee's registration. Concat saves the 32-bit value in the attendee's
profile and uses the NFCRegProxy to program the NFC tag. It writes the password to the appropriate page
on the tag (0x2b for NTAG213, 0x85 for NTAG215 and 0xe5 for NTAG216). It also sets the AUTHLIM value to
4, which means the tag will self-descruct after 4 unsuccessful auth attempts. It also sets
the PROT bit in the config page to 1 to stop read access without the password. AUTH0 in the config page is set
to 0x6 so that the UID can be read without the password.

For verification, the app first reads the tag's UID. It then checks in Concat to see if any attendee is registered
with that UID, and if so, attempts to auth to the card with PWD_AUTH with the stored 32-bit value programmed
at registration. If that is successful the validator then loads the memory content from the tag

## Attack vectors

This method has the vulnerability that if someone monitors the NFC traffic between a valid reader and valid card
(or monitors the registration programming) they will obtain the 32 bit value for that card and be able to
make another one with the same parameters. This will not enable them to impersonate anyone other than the
badge they monitored. Care should be taken by staff operating validators and registration terminals to prevent
people from monitoring NFC traffic between readers and tags. This system is only designed to stop casual cloning
of these NFC tags.

## Data stored on card

Data will be stored on the card starting at page 6. The data format will follow this convention (known as TLV - 
Tag, Length, Value):
 
- 1 byte: Tag (see tag table below) 
- 1 byte: Length (excluding tag and length bytes) - Can be zero
- 0-length bytes: Content. Tag specific data

This system allows any reader to scan through the data to find tags that it understands and skip over unknown
tags. Numeric values are always stored in big endian format (most significant byte first). Because the length
field specifies how long the value stored is, any length integer can be stored. For example, the value 128 can
be stored using a length of 1, but the value 2000 can be stored using 2 bytes (stored as 0x07, 0xd0).

Timestamps are encoded using UTC "unix" time in seconds, unless otherwise specified. This should be a 64-bit (8 byte)
value.

### Tags

#### Tag 0x00

This is a special tag which will always have a langth of zero meaning there are no more tags.

#### Tag 0x01

This tag stores the ConCat ID (attendee ID)

Content: This content will follow the same TLV format, but with the following tags are defined:

- Tag 0x00: Not valid
- Tag 0x01: UserID (int)
- Tag 0x02: ConventionID (int)

#### Tag 0x02

This tag should be the last tag stored and will serve as the signature tag. It will contain an SHA256 ECDSA signature of all
TLVs present before this tag. In the event more than one of these tags is present, the signature will include all the
data since the last signature tag. Multiple signatures should be avoided as this occupies 74 bytes on the tag.

Equivalent openssl commands to verify signature implementation:

Create private key: `openssl ecparam -name secp256k1 -genkey -noout -out ec-secp256k1-priv-key.pem`

Create public key: `openssl ec -in ec-secp256k1-priv-key.pem -pubout > ec-secp256k1-pub-key.pem`

Create signature using private key: `openssl dgst -sha256 -sign ec-secp256k1-priv-key.pem data-file > signature.bin`

Verify signature using public key: `openssl dgst -sha256 -verify ec-secp256k1-pub-key.pem -signature signature.bin data-file`

#### Tag 0x03

Tag issuance count. Starts at zero. In the case a tag is re-issued, the previous UID in concat will be removed from the
attendee's account and the new UID updated, effectively making the old tag unusable (since a reader won't find the
password for the old UID anymore)

#### Tag 0x04

Tag encoding timestamp. This is the time this tag was last written. If any part of the tag is updated, this field
should be updated as well.

#### Tag 0x05

Tag expiry timestamp. This tag should be considered invalid after this time.