#!/usr/bin/env python3
"""
Sincronizador del Data Lake SIGIT.
Usa azure-storage-blob SDK para leer blobs de Azurite con autenticacion.
Se actualiza automaticamente cada 5 segundos.

Uso local:  python3 datalake-demo/sync.py
En Docker:  corre automaticamente como servicio datalake-sync
"""
import json, os, time

try:
    from azure.storage.blob import BlobServiceClient
except ImportError:
    print("Instalando azure-storage-blob...")
    import subprocess, sys
    subprocess.check_call([sys.executable, '-m', 'pip', 'install',
                           'azure-storage-blob', '--quiet'])
    from azure.storage.blob import BlobServiceClient

DEMO_DIR = os.environ.get('SYNC_OUTPUT_DIR', os.path.dirname(os.path.abspath(__file__)))

CONN_STR  = os.environ.get(
    'DATALAKE_CONNECTION_STRING',
    'DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;'
    'AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;'
    'BlobEndpoint=http://localhost:10000/devstoreaccount1;'
)
CONTAINER = os.environ.get('DATALAKE_CONTAINER', 'datalake-sigit')


def fetch_all_blobs():
    client = BlobServiceClient.from_connection_string(CONN_STR)
    container_client = client.get_container_client(CONTAINER)
    blobs = []
    for blob in container_client.list_blobs():
        try:
            bc = container_client.get_blob_client(blob.name)
            content = bc.download_blob().readall().decode('utf-8')
            blobs.append({'name': blob.name, 'content': content})
        except Exception:
            pass
    return blobs


def detect_type(data):
    if 'saleNumber'     in data:
        return 'sale', data.get('saleNumber', '?')
    if 'orderNumber'    in data:
        return 'purchase-order', data.get('orderNumber', '?')
    if 'transferNumber' in data:
        return 'transfer', data.get('transferNumber', '?')
    if 'alertType'      in data:
        alert = data.get('alertType', '').replace('stock-alert.', '')
        return 'stock-alert', f"prod{data.get('productId','?')}-{alert}"
    if 'direction'      in data:
        return 'inventory-movement', f"id{data.get('id','?')}-{data.get('reason','')}"
    return 'unknown', '?'


def get_date(data):
    for key in ('capturedAt', 'saleDate', 'occurredAt', 'orderDate', 'requestedAt'):
        v = data.get(key)
        if v:
            return str(v)[:10]
    return 'sin-fecha'


def safe(s):
    for c in '/\\ :*?"<>|':
        s = s.replace(c, '_')
    return s


def sync():
    raw_blobs = fetch_all_blobs()

    # Limpia archivos anteriores generados por este script
    for f in os.listdir(DEMO_DIR):
        if f.endswith('.json') and f.startswith('landing__'):
            os.remove(os.path.join(DEMO_DIR, f))

    synced  = 0
    summary = []
    seen    = set()

    for item in raw_blobs:
        try:
            data = json.loads(item['content'])
            tipo, nombre = detect_type(data)
            fecha = get_date(data)
            fname = f'landing__{tipo}__{fecha}__{safe(nombre)}.json'
            base, i = fname, 1
            while fname in seen:
                fname = base.replace('.json', f'_{i}.json')
                i += 1
            fpath = os.path.join(DEMO_DIR, fname)
            with open(fpath, 'w') as f:
                json.dump(data, f, indent=2, default=str)
            seen.add(fname)
            synced += 1
            summary.append((tipo, fname))
        except Exception:
            pass

    return synced, summary


# ── Main ──────────────────────────────────────────────────────────────────────
print("╔══════════════════════════════════════════════╗")
print("║   Data Lake SIGIT — Sincronizador en vivo    ║")
print("╚══════════════════════════════════════════════╝")
print(f"Container : {CONTAINER}")
print(f"Carpeta   : {DEMO_DIR}")
print("Actualiza cada 5 segundos. Ctrl+C para detener.\n")

prev_count = -1
while True:
    try:
        n, summary = sync()
        ts = time.strftime('%H:%M:%S')
        if n != prev_count:
            print(f"\n[{ts}] {n} archivos en el Data Lake:")
            by_type = {}
            for tipo, fname in summary:
                by_type.setdefault(tipo, []).append(fname)
            for tipo in sorted(by_type):
                files = by_type[tipo]
                print(f"  [{tipo}] ({len(files)} archivos)")
                for fname in files:
                    print(f"    -> {fname}")
            prev_count = n
        else:
            print(f"\r[{ts}] {n} archivos sincronizados (sin cambios)   ",
                  end='', flush=True)
    except Exception as e:
        print(f"\r[{time.strftime('%H:%M:%S')}] Error: {e}   ", end='', flush=True)
    time.sleep(5)
