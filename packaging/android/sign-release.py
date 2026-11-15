#!/usr/bin/env python3
"""Sign an unsigned CI artifact with the private permanent key, never a generated key."""
import argparse
import hashlib
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path, help='Extracted CI artifact directory')
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--apksigner', type=Path, required=True)
    args = parser.parse_args()
    expected = (Path(__file__).parent / 'release-signing-cert.sha256').read_text().strip()
    source = args.artifact / 'Horizon-Android-unsigned.apk'
    output = args.artifact / 'Horizon-Android.apk'
    # Check the CI download before signing it. Only the known artifact filename is accepted.
    checksum = (args.artifact / 'Horizon-Android-unsigned.apk.sha256').read_text().split()
    if len(checksum) != 2 or checksum[1] != source.name:
        raise SystemExit('Unexpected unsigned artifact checksum record')
    with source.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != checksum[0]:
            raise SystemExit('Unsigned artifact checksum mismatch')
    subprocess.run([str(args.apksigner), 'sign', '--ks', str(args.keystore.resolve()),
                    '--ks-key-alias', 'horizon', '--ks-pass', 'file:' + str(args.password_file.resolve()),
                    '--out', str(output), str(source)], check=True)
    verification = subprocess.check_output([str(args.apksigner), 'verify', '--print-certs', str(output)], text=True)
    signers = [line.removeprefix('Signer #1 certificate SHA-256 digest: ') for line in verification.splitlines()
               if line.startswith('Signer #1 certificate SHA-256 digest: ')]
    if signers != [expected]:
        output.unlink(missing_ok=True)
        raise SystemExit('APK was not signed by the pinned permanent certificate')
    with output.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    (args.artifact / 'Horizon-Android.apk.sha256').write_text(f'{digest}  {output.name}\n')
    print('Signed and verified:', output.name)
    print('Public certificate SHA-256:', expected)


if __name__ == '__main__':
    main()
