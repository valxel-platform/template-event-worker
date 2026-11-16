# contracts/

Written by the Valxel SDLC tool, never by hand: for each data contract this repo reads (the HLD's
Datasets, ODCS v3, approved at G4), `<dataset>.json` is the JSON Schema of its events' data and
`lock.yaml` lists each dataset's version, hash and topic. They ship in the image, so events are
checked against them at run time without reading GitHub. A contract changes only through the
design; the tool sends a task back whose `lock.yaml` doesn't match the approved contracts.
