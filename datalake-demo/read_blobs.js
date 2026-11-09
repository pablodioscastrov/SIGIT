const fs = require('fs');
const db = JSON.parse(fs.readFileSync('/data/__azurite_db_blob__.json'));
const blobs = db.collections.find(c => c.name === '$BLOBS_COLLECTION$').data;

blobs.forEach(blob => {
  const p = blob.persistency;
  if (!p || !p.id) return;
  try {
    const buf = fs.readFileSync('/data/__blobstorage__/' + p.id);
    const content = buf.slice(p.offset, p.offset + p.count).toString('utf8');
    process.stdout.write(JSON.stringify({ name: blob.name, content: content }) + '\n');
  } catch(e) {}
});
