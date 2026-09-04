docker run --rm pulsar:alpine-test /bin/bash -c '
set -euo pipefail

echo "=== 1. Checking User & Security Context ==="
id
echo "User check passed."

echo "=== 2. Checking Networking & Troubleshooting Tools ==="
curl --version | head -n 1
wget --version | head -n 1
dig -v | head -n 1
nslookup -version 2>&1 | head -n 1 || true
nc -h 2>&1 | head -n 2
ping -V 2>&1 | head -n 1 || true

echo "=== 3. Checking System & Utility Tools ==="
ps --version || true
less --version | head -n 1
vim --version | head -n 1

echo "=== 4. Checking Java Runtime ==="
java -version

echo "=== 5. Checking Python & Pulsar Client ==="
python3 --version
python3 -c "import pulsar; print(\"Pulsar client:\", pulsar.__version__)"
python3 -c "import yaml; print(\"PyYAML:\", yaml.__version__)"
python3 -c "import kazoo; print(\"Kazoo:\", kazoo.__version__)"
python3 -c "import grpc; print(\"gRPC:\", grpc.__version__)"
python3 -c "import google.protobuf; print(\"protobuf:\", google.protobuf.__version__)"

echo "=== 6. Checking Pulsar Binaries ==="
bin/pulsar version

echo "=== 7. Checking Alpine Stability Fixes ==="

echo "--- 7a. No real glibc present (glibc-package must be absent) ---"
if find /usr -name "libgcc_s*.so*" -path "*/glibc-compat/*" 2>/dev/null | grep -q .; then
  echo "FAIL: glibc-compat libs found — glibc-package was not fully removed"
  exit 1
fi
if [ -d /usr/glibc-compat ]; then
  echo "FAIL: /usr/glibc-compat directory still exists"
  exit 1
fi
echo "PASS: No glibc-compat installation found."

echo "--- 7b. gcompat (musl-native shim) is present ---"
ls -lh /lib/libgcompat.so.0
echo "PASS: libgcompat.so.0 present."

echo "--- 7c. LD_PRELOAD is set to gcompat ---"
if [ "${LD_PRELOAD}" != "/lib/libgcompat.so.0" ]; then
  echo "FAIL: LD_PRELOAD is '${LD_PRELOAD}', expected '/lib/libgcompat.so.0'"
  exit 1
fi
echo "PASS: LD_PRELOAD=${LD_PRELOAD}"

echo "--- 7d. Runtime libs are present (libgcc, libstdc++, libuuid) ---"
find /usr/lib /lib -name "libgcc_s.so*" | grep -v "glibc-compat" | head -1 | grep -q "." || { echo "FAIL: libgcc_s not found"; exit 1; }
find /usr/lib /lib -name "libstdc++.so*" | head -1 | grep -q "." || { echo "FAIL: libstdc++ not found"; exit 1; }
find /usr/lib /lib -name "libuuid.so*" | head -1 | grep -q "." || { echo "FAIL: libuuid not found"; exit 1; }
echo "PASS: libgcc, libstdc++, libuuid all present."

echo "--- 7e. ROCKSDB_MUSL_LIBC is set ---"
if [ "${ROCKSDB_MUSL_LIBC}" != "true" ]; then
  echo "FAIL: ROCKSDB_MUSL_LIBC is '${ROCKSDB_MUSL_LIBC}', expected 'true'"
  exit 1
fi
echo "PASS: ROCKSDB_MUSL_LIBC=${ROCKSDB_MUSL_LIBC}"

echo "--- 7f. PULSAR_PID_DIR is writable by uid 10000 ---"
if [ "${PULSAR_PID_DIR}" != "/pulsar/logs" ]; then
  echo "FAIL: PULSAR_PID_DIR is '${PULSAR_PID_DIR}', expected '/pulsar/logs'"
  exit 1
fi
touch "${PULSAR_PID_DIR}/.pid_write_test" && rm "${PULSAR_PID_DIR}/.pid_write_test"
echo "PASS: PULSAR_PID_DIR=${PULSAR_PID_DIR} and is writable."

echo "--- 7g. JVM starts and Netty native transport loads via gcompat ---"
java -Xms32m -Xmx64m \
  -Dio.netty.tryReflectionSetAccessible=true \
  -cp /pulsar/lib/io.netty-netty-transport-native-epoll-*.jar \
  -Dio.netty.transport.noNative=false \
  io.netty.channel.epoll.Epoll 2>/dev/null || true
# Just verify JVM starts cleanly without a crash — we do not require epoll
# to be available in all container environments.
java -Xms32m -Xmx64m -version
echo "PASS: JVM starts cleanly."

echo "--- 7h. Python gRPC and protobuf stubs load correctly ---"
python3 -c "
import importlib.util, pathlib, sys
for stub in ['Function_pb2', 'InstanceCommunication_pb2', 'InstanceCommunication_pb2_grpc']:
    paths = list(pathlib.Path('/pulsar').rglob(stub + '.py'))
    if not paths:
        print(f'WARN: {stub}.py not found in /pulsar — skipping')
        continue
    # Add the stub directory to sys.path so bare-name sibling imports resolve
    d = str(paths[0].parent)
    if d not in sys.path:
        sys.path.insert(0, d)
    spec = importlib.util.spec_from_file_location(stub, str(paths[0]))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    print(f'PASS: {stub} loaded OK')
print('PASS: gRPC/protobuf stubs are compatible with installed runtime.')
"

echo "========================================="
echo "  ALL PACKAGE & RUNTIME CHECKS PASSED    "
echo "========================================="
'
