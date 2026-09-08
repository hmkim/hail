import hail as hl

GOLD_STD = 'gs://hail-us-central1-vep/vep_examplars/vep_no_csq_4dc19bc1b.mt/'
GOLD_STD_CSQ = 'gs://hail-us-central1-vep/vep_examplars/vep_csq_4dc19bc1b.mt/'

for path, csq in [(GOLD_STD, False), (GOLD_STD_CSQ, True)]:
    print(f"Checking 'hl.vep' replicates on '{path}'")
    expected = hl.read_matrix_table(path)
    expected_rows = expected.rows().select('vep')
    actual = hl.vep(
        expected_rows.select(), 'gs://hail-us-central1-vep/vep85-loftee-gcloud-testing.json', csq=csq
    ).drop('vep_proc_id')
    actual._force_count()
    assert actual._same(expected_rows), f"VEP output did not match the golden dataset for {path}"
    print('TEST PASSED')
