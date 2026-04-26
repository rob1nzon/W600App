#!/usr/bin/env python3
"""
Reimplementation of SmartLinkCore::CLFSR encrypt from libbtsdk-lib.so
Based on ARM64 disassembly of:
  - Loop_Key(state16)     @ 0x6af2c
  - Encrypt_OneByte(&byte, state16) @ 0x6af48
  - Encrypt_Data(key16, data, len)  @ 0x6b05c
  - JNI wrapper            @ 0x6b2f0

Algorithm: 16-bit LFSR stream cipher, XOR keystream with plaintext bytes.
Polynomial mask: 0xD103 (-0x2EFD in signed)
"""

def lfsr_step(state):
    feedback = (state >> 15) & 1
    new_state = (state << 1) & 0xFFFF
    if feedback:
        new_state ^= 0xD103
    new_state |= feedback
    return new_state & 0xFFFF, feedback


def encrypt_one_byte(byte_val, state):
    result = 0
    for i in range(7, -1, -1):
        state, feedback = lfsr_step(state)
        bit = ((byte_val >> i) & 1) ^ feedback
        result |= (bit << i)
    return result & 0xFF, state


def encrypt_data(key, data):
    state = key & 0xFFFF
    out = bytearray(len(data))
    for i, b in enumerate(data):
        out[i], state = encrypt_one_byte(b, state)
    return bytes(out), state


def encrypt_data_jni(key_int, data_bytes):
    state = key_int & 0xFFFF
    out = bytearray(len(data_bytes))
    for i, b in enumerate(data_bytes):
        out[i], state = encrypt_one_byte(b, state)
    return bytes(out)


print("=" * 60)
print("LFSR Encrypt Algorithm Test")
print("=" * 60)

# Test 1: Symmetry (encrypt twice = identity)
print("\n[Test 1] Symmetry: encrypt(encrypt(x)) == x")
for key in [0x0000, 0x1234, 0xFFFF, 0xD103, 0x8000]:
    data = bytes(range(256))
    encrypted, _ = encrypt_data(key, data)
    decrypted, _ = encrypt_data(key, encrypted)
    assert decrypted == data, f"FAIL for key=0x{key:04x}"
    print(f"  key=0x{key:04x}: OK")

# Test 2: Known LFSR sequence
print("\n[Test 2] LFSR state sequence for key=0x0001")
state = 0x0001
for i in range(16):
    state, fb = lfsr_step(state)
    print(f"  step {i+1}: state=0x{state:04x} feedback={fb}")

# Test 3: Keystream for key=0x0000
print("\n[Test 3] Keystream for key=0x0000 (all zeros)")
data = b'\x00' * 8
encrypted, _ = encrypt_data(0x0000, data)
print(f"  encrypt(0x0000, 8x\\x00) = {encrypted.hex()}")

# Test 4: Verify against known handshake bytes from LensMoo btsnoop
# Phone challenge (0001 EXECUTE): 61 bytes 0x01..0x3D
phone_challenge = bytes(range(0x01, 0x3E))
print(f"\n[Test 4] Phone challenge ({len(phone_challenge)} bytes): {phone_challenge.hex()}")

# The handshake response from LensMoo btsnoop (64 bytes):
# First 16 bytes = "RnewylVa5xonajRn" (ASCII header)
# Middle 32 bytes = encryptData(key, device_challenge)
# Last 16 bytes = "RnewylVa5xonajBO" (ASCII footer)
response_hex = (
    "526e6577796c566135786f6e616a526e"
    "23de52d0c91345ef984460f66c92165e"
    "6a1cfa224ad8817851f2442ba5691e7a"
    "526e6577796c566135786f6e616a424f"
)
response_bytes = bytes.fromhex(response_hex)
print(f"  Full response (64 bytes): {response_hex}")

header = response_bytes[:16]
middle = response_bytes[16:48]
footer = response_bytes[48:]
print(f"  Header:  {header} ({header.hex()})")
print(f"  Middle (encrypted):  {middle.hex()}")
print(f"  Footer:  {footer} ({footer.hex()})")

# verificationCmd extracts middle 32 bytes, encrypts with key, XORs with another value
# Without knowing the key and device challenge, we can't fully verify,
# but we can show the structure.

# Test 5: Check if encrypting known data produces consistent results
print("\n[Test 5] Consistency test")
key = 0xABCD
data = b"Hello, W600!"
enc1, _ = encrypt_data(key, data)
enc2, _ = encrypt_data(key, data)
assert enc1 == enc2
print(f"  key=0x{key:04x} data={data}")
print(f"  encrypted: {enc1.hex()}")
dec, _ = encrypt_data(key, enc1)
assert dec == data
print(f"  decrypted: {dec}")

# Test 6: Single byte encryption trace
print("\n[Test 6] Trace encrypt_one_byte(0xAA, state=0x1234)")
state = 0x1234
byte_val = 0xAA
print(f"  Input: byte=0x{byte_val:02x} state=0x{state:04x}")
print(f"  LFSR bits (MSB first):")
result = 0
for i in range(7, -1, -1):
    state, fb = lfsr_step(state)
    plaintext_bit = (byte_val >> i) & 1
    cipher_bit = plaintext_bit ^ fb
    result |= (cipher_bit << i)
    print(f"    bit{i}: lfsr_out={fb} plain={plaintext_bit} cipher={cipher_bit} -> state=0x{state:04x}")
print(f"  Result: 0x{result:02x}")

print("\n" + "=" * 60)
print("All tests passed!")
print("=" * 60)
