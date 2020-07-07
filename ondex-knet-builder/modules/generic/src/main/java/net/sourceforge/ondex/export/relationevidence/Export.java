package net.sourceforge.ondex.export.relationevidence;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sourceforge.ondex.InvalidPluginArgumentException;
import net.sourceforge.ondex.annotations.Authors;
import net.sourceforge.ondex.annotations.Custodians;
import net.sourceforge.ondex.annotations.Status;
import net.sourceforge.ondex.annotations.StatusType;
import net.sourceforge.ondex.args.ArgumentDefinition;
import net.sourceforge.ondex.args.FileArgumentDefinition;
import net.sourceforge.ondex.args.StringArgumentDefinition;
import net.sourceforge.ondex.core.ConceptAccession;
import net.sourceforge.ondex.core.ConceptName;
import net.sourceforge.ondex.core.EvidenceType;
import net.sourceforge.ondex.core.ONDEXConcept;
import net.sourceforge.ondex.core.ONDEXRelation;
import net.sourceforge.ondex.event.type.DataFileErrorEvent;
import net.sourceforge.ondex.event.type.DataFileMissingEvent;
import net.sourceforge.ondex.event.type.EvidenceTypeMissingEvent;
import net.sourceforge.ondex.event.type.GeneralOutputEvent;
import net.sourceforge.ondex.event.type.WrongParameterEvent;
import net.sourceforge.ondex.export.ONDEXExport;

/**
 * Statistics module to compared relations generated by different mapping
 * methods.
 * 
 * @author taubertj
 */
@Authors(authors = { "Jan Taubert" }, emails = { "jantaubert at users.sourceforge.net" })
@Custodians(custodians = { "Jan Taubert" }, emails = { "jantaubert at users.sourceforge.net" })
@Status(description = "Tested by Jan Taubert (January 2013)", status = StatusType.EXPERIMENTAL)
public class Export extends ONDEXExport {

	/**
	 * Returns the name of this statistics module.
	 * 
	 * @return String
	 */
	public String getName() {
		return "Relations per evidence statistics";
	}

	/**
	 * Returns the version of this statistics module.
	 * 
	 * @return String
	 */
	public String getVersion() {
		return "01.01.2013";
	}

	@Override
	public String getId() {
		return "relationevidence";
	}

	/**
	 * Returns arguments for this statistics module.
	 * 
	 * @return ArgumentDefinition<?>[]
	 */
	public ArgumentDefinition<?>[] getArgumentDefinitions() {
		StringArgumentDefinition evidenceARG = new StringArgumentDefinition(
				ArgumentNames.EVIDENCE_ARG, ArgumentNames.EVIDENCE_ARG_DESC,
				true, null, true);

		FileArgumentDefinition outputARG = new FileArgumentDefinition(
				FileArgumentDefinition.EXPORT_FILE,
				FileArgumentDefinition.EXPORT_FILE_DESC, true, false, false);

		return new ArgumentDefinition<?>[] { evidenceARG, outputARG };
	}

	/**
	 * Starts AbstractONDEXGraph and starts processing.
	 */
	public void start() throws InvalidPluginArgumentException {

		boolean ready = true;

		String output = (String) getArguments().getUniqueValue(
				FileArgumentDefinition.EXPORT_FILE);
		if (output == null) {
			fireEventOccurred(new WrongParameterEvent(
					"No output file specified.", getCurrentMethodName()));
			ready = false;
		} else {
			fireEventOccurred(new GeneralOutputEvent("Using output file: "
					+ output, getCurrentMethodName()));
		}

		// get list of evidence types from arguments
		List<EvidenceType> evidences = new ArrayList<EvidenceType>();
		for (String name : getArguments().getObjectValueList(
				ArgumentNames.EVIDENCE_ARG, String.class)) {
			EvidenceType et = graph.getMetaData().getEvidenceType(name.trim());
			if (et == null) {
				fireEventOccurred(new EvidenceTypeMissingEvent(
						"Specified EvidenceType not found: " + name,
						getCurrentMethodName()));
				ready = false;
			} else {
				evidences.add(et);
			}
		}

		if (ready) {

			try {
				BufferedWriter writer = new BufferedWriter(new FileWriter(
						output));
				writer.write("Totals:\n");

				// get concept ids association in mappings
				Map<EvidenceType, Map<Integer, List<Integer>>> mapping = new Hashtable<EvidenceType, Map<Integer, List<Integer>>>();
				for (EvidenceType et : evidences) {
					mapping.put(et, new Hashtable<Integer, List<Integer>>());
					Set<ONDEXRelation> view = graph
							.getRelationsOfEvidenceType(et);
					writer.write(et.getId() + ": " + view.size() + "\n");
					for (ONDEXRelation r : view) {
						ONDEXConcept from = r.getFromConcept();
						ONDEXConcept to = r.getToConcept();
						if (!mapping.get(et).containsKey(from.getId())) {
							mapping.get(et).put(from.getId(),
									new ArrayList<Integer>());
						}
						mapping.get(et).get(from.getId()).add(to.getId());
					}
				}
				writer.write("\n");

				// equal and different relations
				Map<EvidenceTypeTuple, Map<Integer, List<Integer>>> missing = new Hashtable<EvidenceTypeTuple, Map<Integer, List<Integer>>>();
				writer.write("Matching matrix:\n");
				writer.write("\t");
				for (EvidenceType et : evidences) {
					writer.write(et.getId() + "\t");
				}
				writer.write("\n");
				for (EvidenceType et1 : evidences) {
					writer.write(et1.getId() + "\t");
					Map<Integer, List<Integer>> map1 = mapping.get(et1);
					for (EvidenceType et2 : evidences) {
						int matches = 0;
						EvidenceTypeTuple tuple = new EvidenceTypeTuple(et1,
								et2);
						missing.put(tuple,
								new Hashtable<Integer, List<Integer>>());
						Map<Integer, List<Integer>> map2 = mapping.get(et2);
						for (Integer key : map1.keySet()) {
							for (Integer rel : map1.get(key)) {
								if (map2.containsKey(key)
										&& map2.get(key).contains(rel)) {
									matches++;
								} else {
									if (!missing.get(tuple).containsKey(key)) {
										missing.get(tuple).put(key,
												new ArrayList<Integer>());
									}
									missing.get(tuple).get(key).add(rel);
								}
							}
						}
						writer.write(matches + "\t");
					}
					writer.write("\n");
				}
				writer.write("\n");

				// specific look at none matching relations
				writer.write("Missing relations:\n");
				for (EvidenceTypeTuple tuple : missing.keySet()) {
					int size = 0;
					Map<Integer, List<Integer>> map = missing.get(tuple);
					for (Integer integer1 : map.keySet()) {
						size += map.get(integer1).size();
					}
					writer.write("Found by " + tuple.getEvidenceType1().getId()
							+ " but not by " + tuple.getEvidenceType2().getId()
							+ " " + size + "\n");
					for (Integer key : map.keySet()) {
						ONDEXConcept from = graph.getConcept(key);
						for (Integer integer : map.get(key)) {
							ONDEXConcept to = graph.getConcept(integer);
							// concept ids
							writer.write(from.getId() + "\t" + to.getId()
									+ "\t");
							// concept pids
							writer.write(from.getPID() + "\t" + to.getPID()
									+ "\t");
							// concept annotations
							writer.write(from.getAnnotation() + "\t"
									+ to.getAnnotation() + "\t");
							// concept name lists
							ArrayList<String> fromNames = new ArrayList<String>();
							for (ConceptName name : from.getConceptNames()) {
								fromNames.add(name.getName());
							}
							ArrayList<String> toNames = new ArrayList<String>();
							for (ConceptName name : to.getConceptNames()) {
								toNames.add(name.getName());
							}
							writer.write(fromNames + "\t" + toNames + "\t");
							ArrayList<String> fromAccs = new ArrayList<String>();
							for (ConceptAccession acc : from
									.getConceptAccessions()) {
								fromAccs.add(acc.getAccession());
							}
							ArrayList<String> toAccs = new ArrayList<String>();
							for (ConceptAccession acc : to
									.getConceptAccessions()) {
								toAccs.add(acc.getAccession());
							}
							writer.write(fromAccs + "\t" + toAccs + "\t");
							writer.write("\n");
						}
					}
				}
				writer.write("\n");

				writer.flush();
				writer.close();
			} catch (FileNotFoundException fnfe) {
				fireEventOccurred(new DataFileMissingEvent(fnfe.getMessage(),
						getCurrentMethodName()));
			} catch (IOException ioe) {
				fireEventOccurred(new DataFileErrorEvent(ioe.getMessage(),
						getCurrentMethodName()));
			}
		}
	}

	/**
	 * Does not require a Lucene index.
	 * 
	 * @return false
	 */
	public boolean requiresIndexedGraph() {
		return false;
	}

	/**
	 * Class represents a tuple of evidence types, overrides equals methode.
	 * 
	 * @author taubertj
	 */
	private class EvidenceTypeTuple {

		private EvidenceType et1;

		private EvidenceType et2;

		public EvidenceTypeTuple(EvidenceType et1, EvidenceType et2) {
			this.et1 = et1;
			this.et2 = et2;
		}

		public EvidenceType getEvidenceType1() {
			return et1;
		}

		public EvidenceType getEvidenceType2() {
			return et2;
		}

		@Override
		public boolean equals(Object arg0) {
			if (arg0 instanceof EvidenceTypeTuple) {
				EvidenceTypeTuple ett = (EvidenceTypeTuple) arg0;
				return ett.et1.equals(et1) && ett.et2.equals(et2);
			}
			return false;
		}

		@Override
		public int hashCode() {
			return et1.hashCode() + et2.hashCode();
		}
	}

	@Override
	public String[] requiresValidators() {
		return new String[0];
	}

}
