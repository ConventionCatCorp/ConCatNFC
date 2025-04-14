# ConCatNFC

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
tags

### Tags

#### Tag 0

This is a special tag which will always have a langth of zero meaning there are no more tags.

#### Tag 1

This tag stores the ConCat ID (attendee ID)

Length: variable

Content: This content will follow the same TLV format, but with the following tags are defined:

- Tag 0: Not valid
- Tag 1: UserID
- Tag 2: ConventionID

#### Tag 2

This tag should be the last tag stored and will serve as the signature tag. It will contain an SHA256 ECDSA signature of all
TLVs present before this tag. In the event more than one of these tags is present, the signature will include all the
data since the last signature tag. Multiple signatures should be avoided as this occupies 74 bytes on the tag.

Equivalent openssl commands to verify signature implementation:

Create private key: `openssl ecparam -name secp256k1 -genkey -noout -out ec-secp256k1-priv-key.pem`

Create public key: `openssl ec -in ec-secp256k1-priv-key.pem -pubout > ec-secp256k1-pub-key.pem`

Create signature using private key: `openssl dgst -sha256 -sign ec-secp256k1-priv-key.pem data-file > signature.bin`

Verify signature using public key: `openssl dgst -sha256 -verify ec-secp256k1-pub-key.pem -signature signature.bin data-file`
