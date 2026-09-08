import hail as hl

GNOMAD_CHR22_FIRST_1000 = "gs://hail-us-central1-vep/vep_examplars/gnomad3_chr22_first_1000.mt"

for path, csq in [(GNOMAD_CHR22_FIRST_1000, False)]:
    print(f"Checking 'hl.vep' replicates on '{path}'")
    expected = hl.read_matrix_table(path)
    expected_rows = expected.rows().select('vep')
    actual = hl.vep(
        expected_rows.select(), 'gs://hail-us-central1-vep/vep95-GRCh38-loftee-gcloud.json', csq=csq
    ).drop('vep_proc_id')
    actual._force_count()
    assert actual._same(expected_rows), f"VEP output did not match the golden dataset for {path}"
    print('TEST PASSED')
