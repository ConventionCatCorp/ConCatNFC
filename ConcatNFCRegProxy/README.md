# ConcatNFCRegProxy

Under this directory, implementations for different platforms for the agent
that runs on local machines containing a web browser where ConCat users
who are running registration at a convention are programming NFC tags. This
agent accepts requests from localhost to query and program NFC tags.

These systems are intended to work with a USB NFC reader. The initial reader tested is an ACR122U,
which imnplements a PC/SC interface. Documentation on talking to the interface can be found here: [ACR122U – Application Programming Interface](https://www.acs.com.hk/download-manual/419/API-ACR122U-2.04.pdf)
