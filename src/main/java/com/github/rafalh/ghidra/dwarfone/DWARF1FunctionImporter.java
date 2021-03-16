package com.github.rafalh.ghidra.dwarfone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.rafalh.ghidra.dwarfone.model.AddrAttributeValue;
import com.github.rafalh.ghidra.dwarfone.model.AttributeName;
import com.github.rafalh.ghidra.dwarfone.model.DebugInfoEntry;
import com.github.rafalh.ghidra.dwarfone.model.RefAttributeValue;
import com.github.rafalh.ghidra.dwarfone.model.Tag;

import ghidra.app.util.importer.MessageLog;
import ghidra.program.database.function.OverlappingFunctionException;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.ParameterImpl;
import ghidra.program.model.listing.ReturnParameterImpl;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;

public class DWARF1FunctionImporter {
	private final DWARF1Program dwarfProgram;
	private final MessageLog log;
	private final DWARF1TypeManager dwarfTypeManager;
	private final DWARF1TypeExtractor typeExtractor;
	private final FunctionManager functionManager;
	
	DWARF1FunctionImporter(DWARF1Program dwarfProgram, MessageLog log, DWARF1TypeManager dwarfTypeManager, DWARF1TypeExtractor typeExtractor) {
		this.dwarfProgram = dwarfProgram;
		this.log = log;
		this.dwarfTypeManager = dwarfTypeManager;
		this.typeExtractor = typeExtractor;
		functionManager = dwarfProgram.getProgram().getFunctionManager();
	}
	
	void processSubrountine(DebugInfoEntry die) {
		Optional<String> nameOptional = DWARF1ImportUtils.extractName(die);
		Optional<AddrAttributeValue> lowPcAttributeOptional = die.getAttribute(AttributeName.LOW_PC);
		Optional<AddrAttributeValue> highPcAttributeOptional = die.getAttribute(AttributeName.HIGH_PC);
		
		if (nameOptional.isEmpty() || lowPcAttributeOptional.isEmpty() || highPcAttributeOptional.isEmpty()) {
			return;
		}
		
		String name = nameOptional.get();
		long lowPc = lowPcAttributeOptional.get().get();
		long highPc = highPcAttributeOptional.get().get();
		//log.appendMsg(name + " " + Long.toHexString(lowPc.longValue()));

		Address lowAddr = dwarfProgram.toAddr(lowPc);
		Address highAddr = dwarfProgram.toAddr(highPc);
		
		// Prefix name with the class name if this is a member function
		Optional<DataType> classDtOpt = determineMemberClassType(die);
		if (classDtOpt.isPresent() && !name.contains(classDtOpt.get().getName())) {
			name = classDtOpt.get().getName() + "::" + name;
		}
		
		DataType returnDt = typeExtractor.extractDataType(die);
		
		Function fun = functionManager.getFunctionAt(lowAddr);
		try {
			if (fun == null) {
				AddressSetView funSet = dwarfProgram.getSet().intersectRange(lowAddr, highAddr);
				fun = functionManager.createFunction(name, lowAddr, funSet, SourceType.IMPORTED);
			} else {
				fun.setName(name, SourceType.IMPORTED);
			}
			
			Variable returnParam = new ReturnParameterImpl(returnDt, dwarfProgram.getProgram());
			List<Variable> params = new ArrayList<>();
			for (DebugInfoEntry childDie : die.getChildren()) {
				if (childDie.getTag() == Tag.FORMAL_PARAMETER) {
					String paramName = DWARF1ImportUtils.extractName(childDie).orElse(null);
					DataType dt = typeExtractor.extractDataType(childDie);
					params.add(new ParameterImpl(paramName, dt, dwarfProgram.getProgram()));
				}
			}
		
			fun.updateFunction(null, returnParam, params, FunctionUpdateType.DYNAMIC_STORAGE_FORMAL_PARAMS, true, SourceType.IMPORTED);
		} catch (DuplicateNameException | InvalidInputException | OverlappingFunctionException e) {
			log.appendException(e);
		}
	}
	
	private Optional<DataType> determineMemberClassType(DebugInfoEntry die) {
		// Function defined in class body
		if (die.getParent().getTag() == Tag.CLASS_TYPE) {
			return Optional.of(dwarfTypeManager.getUserDataType(die.getParent().getRef()));
		}
		// Function defined outside of the class body should have AT_member attribute
		Optional<RefAttributeValue> memberAttributeOptional = die.getAttribute(AttributeName.MEMBER);
		if (memberAttributeOptional.isPresent()) {
			return Optional.of(dwarfTypeManager.getUserDataType(memberAttributeOptional.get().get()));
		}
		// Determine the class based on the "this" parameter because for some compilers (e.g. PS2) normal
		// ways does not work...
		for (DebugInfoEntry childDie : die.getChildren()) {
			if (childDie.getTag() == Tag.FORMAL_PARAMETER && Optional.of("this").equals(DWARF1ImportUtils.extractName(childDie))) {
				DataType dt = typeExtractor.extractDataType(childDie);
				if (dt instanceof Pointer) {
					dt = ((Pointer) dt).getDataType();
				}
				return Optional.of(dt);
			}
		}
		return Optional.empty();
	}
}
