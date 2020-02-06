# Copyright 2018 ICON Foundation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
""" A class for icx authorization of Peer"""

import binascii
import hashlib
import logging
from typing import Union, Type, TypeVar

import eth_keyfile
from secp256k1 import Base, ALL_FLAGS
from secp256k1 import PrivateKey, PublicKey

from loopchain.crypto.cert_serializers import DerSerializer, PemSerializer

T = TypeVar('T', bound='SignVerifier')


class SignVerifier:
    _base = Base(None, ALL_FLAGS)
    _pri = PrivateKey(ctx=_base.ctx)

    def __init__(self):
        self.address: str = None

    def verify_address(self, pubkey: bytes):
        new_address = self.address_from_pubkey(pubkey)
        if new_address != self.address:
            raise RuntimeError(f"Address is not valid."
                               f"Address({new_address}), "
                               f"Expected({self.address}")

    def verify_data(self, origin_data: bytes, signature: bytes):
        self.verify_signature(origin_data, signature, False)

    def verify_hash(self, origin_data: bytes, signature):
        self.verify_signature(origin_data, signature, True)

    def verify_signature(self, origin_data: bytes, signature: bytes, is_hash: bool):
        try:
            origin_signature, recover_code = signature[:-1], signature[-1]
            recoverable_sig = self._pri.ecdsa_recoverable_deserialize(origin_signature, recover_code)
            pub = self._pri.ecdsa_recover(origin_data,
                                          recover_sig=recoverable_sig,
                                          raw=is_hash,
                                          digest=hashlib.sha3_256)
            extract_pub = PublicKey(pub, ctx=self._base.ctx).serialize(compressed=False)
            return self.verify_address(extract_pub)
        except Exception as e:
            raise RuntimeError(f"signature verification fail : {origin_data} {signature}\n"
                               f"{e}")

    @classmethod
    def address_from_pubkey(cls, pubkey: bytes):
        hash_pub = hashlib.sha3_256(pubkey[1:]).hexdigest()
        return f"hx{hash_pub[-40:]}"

    @classmethod
    def address_from_prikey(cls, prikey: Union[bytes, PrivateKey]):
        prikey = prikey if isinstance(prikey, PrivateKey) else PrivateKey(prikey)
        pubkey = prikey.pubkey.serialize(compressed=False)
        return cls.address_from_pubkey(pubkey)

    @classmethod
    def from_address(cls: Type[T], address: str) -> T:
        verifier = SignVerifier()
        verifier.address = address
        return verifier

    @classmethod
    def from_pubkey_file(cls: Type[T], pubkey_file: str) -> T:
        if pubkey_file.endswith('.der'):
            pubkey = DerSerializer.deserialize_public_key_file(pubkey_file)
        elif pubkey_file.endswith('.pem'):
            pubkey = PemSerializer.deserialize_public_key_file(pubkey_file)
        else:
            raise RuntimeError(f"Not supported file {pubkey_file}")
        return cls.from_pubkey(pubkey)

    @classmethod
    def from_pubkey(cls: Type[T], pubkey: bytes) -> T:
        address = cls.address_from_pubkey(pubkey)
        return cls.from_address(address)

    @classmethod
    def from_prikey_file(cls: Type[T], prikey_file: str, password: Union[str, bytes]) -> T:
        if isinstance(password, str):
            password = password.encode()

        try:
            if prikey_file.endswith('.der'):
                prikey = DerSerializer.deserialize_private_key_file(prikey_file, password)
            elif prikey_file.endswith('.pem'):
                prikey = PemSerializer.deserialize_private_key_file(prikey_file, password)
            else:
                with open(prikey_file, 'rb') as file:
                    prikey = eth_keyfile.extract_key_from_keyfile(file, password)
        except Exception:
            raise ValueError("Invalid Password.")
        return cls.from_prikey(prikey)

    @classmethod
    def from_prikey(cls: Type[T], prikey: bytes) -> T:
        address = cls.address_from_prikey(prikey)
        return cls.from_address(address)


class Signer(SignVerifier):
    def __init__(self):
        super().__init__()
        self.private_key: PrivateKey = None

    def sign_data(self, data):
        return self.sign(data, False)

    def sign_hash(self, data):
        return self.sign(data, True)

    def sign(self, data, is_hash: bool):
        if is_hash:
            if isinstance(data, str):
                try:
                    data = data.split("0x")[1] if data.startswith("0x") else data
                    data = binascii.unhexlify(data)
                except Exception as e:
                    logging.error(f"hash data must hex string or bytes \n exception : {e}")
                    return None

        if not isinstance(data, (bytes, bytearray)):
            logging.error(f"data must be bytes \n")
            return None

        raw_sig = self.private_key.ecdsa_sign_recoverable(msg=data,
                                                          raw=is_hash,
                                                          digest=hashlib.sha3_256)
        serialized_sig, recover_id = self.private_key.ecdsa_recoverable_serialize(raw_sig)
        return serialized_sig + bytes((recover_id, ))

    @classmethod
    def from_address(cls: Type[T], address: str) -> T:
        raise TypeError("Cannot create `Signer` from address")

    @classmethod
    def from_pubkey(cls: Type[T], pubkey: bytes) -> T:
        raise TypeError("Cannot create `Signer` from pubkey")

    @classmethod
    def from_pubkey_file(cls: Type[T], pubkey_file: str) -> T:
        raise TypeError("Cannot create `Signer` from pubkey file")

    @classmethod
    def from_prikey_file(cls: Type[T], prikey_file: str, password: Union[str, bytes]) -> T:
        return super().from_prikey_file(prikey_file, password)

    @classmethod
    def from_prikey(cls: Type[T], prikey: Union[bytes, PrivateKey]):
        signer = Signer()
        signer.private_key = prikey if isinstance(prikey, PrivateKey) else PrivateKey(prikey, ctx=cls._base.ctx)
        signer.address = cls.address_from_prikey(prikey)
        return signer

    @classmethod
    def new(cls):
        return cls.from_prikey(PrivateKey())


def long_to_bytes(val, endianness='big'):
    """
    Use :ref:`string formatting` and :func:`~binascii.unhexlify` to
    convert ``val``, a :func:`long`, to a byte :func:`str`.

    :param long val: The value to pack

    :param str endianness: The endianness of the result. ``'big'`` for
      big-endian, ``'little'`` for little-endian.

    If you want byte- and word-ordering to differ, you're on your own.

    Using :ref:`string formatting` lets us use Python's C innards.
    """

    # one (1) hex digit per four (4) bits
    width = val.bit_length()

    # unhexlify wants an even multiple of eight (8) bits, but we don't
    # want more digits than we need (hence the ternary-ish 'or')
    width += 8 - ((width % 8) or 8)

    # format width specifier: four (4) bits per hex digit
    fmt = '%%0%dx' % (width // 4)

    # prepend zero (0) to the width, to zero-pad the output
    s = binascii.unhexlify(fmt % val)

    if endianness == 'little':
        # see http://stackoverflow.com/a/931095/309233
        s = s[::-1]

    return s
